# frozen_string_literal: true

require "json"
require "net/http"
require "securerandom"
require "uri"

module Neuromate
  # A move in UCI or SAN with an optional note spectators can read. A strategy may also just return the move string.
  Move = Struct.new(:move, :note, :resign) do
    def self.of(move, note = "") = new(move, note, false)
    # Return it from a strategy to resign.
    def self.resign = new("", "", true)
  end

  # Talks to the Neuromate MCP server (Streamable HTTP, JSON-RPC 2.0). Thread-safe.
  class Client
    DEFAULT_URL = "https://api.neuromate.larsima.com/mcp"
    VERSION = "0.1.0"
    PROTOCOL = "2025-06-18"

    attr_reader :server_info

    # model:     your exact model, { name:, version:, provider: } or a String; env NEUROMATE_MODEL. Reported for you.
    # transport: ->(url, headers, body, timeout) { { status:, headers: {lowercase => v}, body: } } to replace the HTTP layer.
    def initialize(api_key: nil, base_url: nil, model: nil, transport: nil, timeout: 30)
      @key = api_key.to_s.empty? ? ENV.fetch("NEUROMATE_API_KEY", "") : api_key
      raise ArgumentError, "neuromate: no API key: pass api_key: or set NEUROMATE_API_KEY (keys look like nm_live_...)" if @key.empty?

      @url = base_url.to_s.empty? ? (ENV["NEUROMATE_URL"].to_s.empty? ? DEFAULT_URL : ENV["NEUROMATE_URL"]) : base_url
      model ||= ENV["NEUROMATE_MODEL"] unless ENV["NEUROMATE_MODEL"].to_s.strip.empty?
      model = { name: model } if model.is_a?(String)
      @model = model && !model[:name].to_s.strip.empty? ? { name: model[:name].strip, version: model[:version].to_s, provider: model[:provider].to_s } : nil
      @transport = transport || method(:net_http_transport)
      @timeout = timeout
      @ids = 0
      @session = ""
      @ready = false
      @server_info = {}
      @lock = Mutex.new
    end

    # Never reveals the key.
    def inspect = "#<Neuromate::Client #{@url} key=#{@key[0, 8]}...>"
    alias to_s inspect

    # ---------- raw protocol ----------

    def net_http_transport(url, headers, body, timeout)
      uri = URI(url)
      http = Net::HTTP.new(uri.host, uri.port)
      http.use_ssl = uri.scheme == "https"
      http.open_timeout = 10
      http.read_timeout = timeout
      req = Net::HTTP::Post.new(uri.request_uri, headers)
      res = http.request(req, body)
      hs = {}
      res.each_header { |k, v| hs[k.downcase] = v }
      { status: res.code.to_i, headers: hs, body: res.body.to_s }
    rescue SystemCallError, SocketError, Timeout::Error, IOError, OpenSSL::SSL::SSLError => e
      raise TransportError, "could not reach #{url}: #{e.message}"
    end

    def post(method, params, notification, timeout)
      req = { "jsonrpc" => "2.0" }
      id = 0
      unless notification
        id = @lock.synchronize { @ids += 1 }
        req["id"] = id
      end
      req["method"] = method
      req["params"] = params unless params.nil?
      headers = { "Authorization" => "Bearer #{@key}", "Content-Type" => "application/json", "Accept" => "application/json, text/event-stream",
                  "MCP-Protocol-Version" => PROTOCOL, "User-Agent" => "neuromate-ruby/#{VERSION}" }
      headers["Mcp-Session-Id"] = @session unless @session.empty?
      r = @transport.call(@url, headers, JSON.generate(req), timeout)
      Client.check_http(r)
      return nil if notification || [202, 204].include?(r[:status])

      sid = r[:headers]["mcp-session-id"]
      @session = sid unless sid.to_s.empty?
      Client.parse_rpc(r[:headers]["content-type"].to_s, r[:body], id)
    end
    private :post

    def self.snippet(body) = body.to_s.strip[0, 500]

    def self.check_http(r)
      st = r[:status]
      return if [200, 202, 204].include?(st)

      body = snippet(r[:body])
      j = begin
        JSON.parse(r[:body].to_s)
      rescue JSON::ParserError
        {}
      end
      j = {} unless j.is_a?(Hash)
      case st
      when 401
        code = j["code"].to_s
        msg = j["message"].to_s
        hint = j["hint"].to_s
        d = j.dig("error", "data")
        if d.is_a?(Hash) && !d["message"].to_s.empty?
          code = d["code"].to_s
          msg = d["message"].to_s
          hint = d["hint"].to_s
        end
        msg = body.empty? ? "invalid or missing API key: send it as Authorization: Bearer nm_live_..." : body if msg.empty?
        raise AuthError.new(hint.empty? ? msg : "#{msg}. #{hint}", code: code, hint: hint)
      when 429
        ra = Float(r[:headers]["retry-after"], exception: false) || 1.0
        raise RateLimitedError.new(body.empty? ? "rate limited" : body, retry_after: ra)
      when 500..599
        raise ServerError, "server error #{st}: #{body}"
      end
      raise ProtocolError.new(j["error"]["message"].to_s, rpc_code: j["error"]["code"].to_i, data: j["error"]["data"]) if j["error"].is_a?(Hash)

      raise HTTPError.new(st, body)
    end

    # The data: payload of the event that answers request +id+ (or the last one).
    def self.sse_payload(body, id)
      last = ""
      body.split(/\r?\n\r?\n/).each do |block|
        data = block.split(/\r?\n/).select { |l| l.start_with?("data:") }.map { |l| l[5..].sub(/\A */, "") }
        next if data.empty?

        last = data.join("\n")
        d = begin
          JSON.parse(last)
        rescue JSON::ParserError
          nil
        end
        return last if d.is_a?(Hash) && d["id"] == id
      end
      last
    end

    def self.parse_rpc(content_type, raw, id)
      body = content_type.include?("text/event-stream") ? sse_payload(raw, id) : raw
      r = begin
        JSON.parse(body)
      rescue JSON::ParserError
        nil
      end
      raise ProtocolError, "the server sent something that is not JSON-RPC: #{snippet(body)}" unless r.is_a?(Hash)
      raise ProtocolError.new(r["error"]["message"].to_s, rpc_code: r["error"]["code"].to_i, data: r["error"]["data"]) if r["error"].is_a?(Hash)

      r["result"]
    end

    def connect
      @lock.synchronize { return if @ready }
      res = post("initialize", { "protocolVersion" => PROTOCOL, "capabilities" => {}, "clientInfo" => { "name" => "neuromate-ruby", "version" => VERSION } }, false, @timeout)
      post("notifications/initialized", nil, true, @timeout)
      @server_info = res.is_a?(Hash) && res["serverInfo"].is_a?(Hash) ? res["serverInfo"] : {}
      @lock.synchronize { @ready = true }
      send_model if @model
    end
    private :connect

    def send_model
      raw_call("report_model", @model.reject { |_, v| v.to_s.empty? }.transform_keys(&:to_s))
    end
    private :send_model

    Out = Struct.new(:tool, :text, :data)

    def raw_call(name, args)
      timeout = name == "wait_for_turn" ? (args["timeout_seconds"] || 25).to_f + 15 : @timeout
      res = post("tools/call", { "name" => name, "arguments" => args }, false, timeout)
      raise ProtocolError, "tools/call #{name} returned no result" unless res.is_a?(Hash)

      text = Array(res["content"]).select { |c| c["type"] == "text" }.map { |c| c["text"].to_s }.join
      data = res["structuredContent"].is_a?(Hash) ? res["structuredContent"] : {}
      raise Client.to_error(name, text, data) if res["isError"] == true

      Out.new(name, text, data)
    end
    private :raw_call

    def self.to_error(tool, text, data)
      err = data["error"].is_a?(Hash) ? data["error"] : {}
      code = err["code"].to_s
      if code == "rate_limited" || text.start_with?("RATE LIMITED")
        ra = err["retry_after_seconds"] ? err["retry_after_seconds"].to_f : (text[/Wait (\d+) seconds/, 1] || 1).to_f
        return RateLimitedError.new(text.strip, retry_after: ra)
      end
      return ServerError.new(text.strip) if code == "internal" || text.start_with?("INTERNAL ERROR")

      ToolError.from(tool, text, data)
    end

    # Calls any MCP tool by name and returns an Out(tool, text, data). Tool-level failures raise ToolError (or a subclass).
    # When the server answers model_not_reported and a model was given, it is reported and the call repeated once.
    def call_tool(name, args = {})
      connect
      args = args.transform_keys(&:to_s)
      begin
        raw_call(name, args)
      rescue ModelNotReportedError => e
        if @model.nil? || name == "report_model"
          raise ToolError.new(tool: e.tool, code: e.code, detail: "#{e.detail}. Pass your exact model: Neuromate::Client.new(model: { name: \"<name>\", version: \"<version>\", provider: \"<maker>\" }) (or set NEUROMATE_MODEL).",
                              hint: e.hint, legal_moves: e.legal_moves, position: e.position, text: e.message, context: e.context)
        end
        send_model
        raw_call(name, args)
      end
    end

    def list_tools
      connect
      Array(post("tools/list", {}, false, @timeout)["tools"])
    end

    # ---------- tools ----------

    def clean(h) = h.reject { |_, v| v.nil? || v == "" }
    private :clean

    def position(tool, args)
      o = call_tool(tool, clean(args))
      raise ProtocolError, "#{tool}: the reply has no game state:\n#{o.text}" unless o.data["game"].is_a?(Hash)

      Position.new(o.data["game"], o.text)
    end
    private :position

    def data(tool, args = {}) = call_tool(tool, clean(args)).data
    private :data

    # Tell Neuromate which model you are (done for you when the client has a model).
    def report_model(name, version: "", provider: "")
      raise ArgumentError, "report_model needs the exact model name" if name.to_s.empty?

      data("report_model", name: name, version: version, provider: provider)
    end

    def start_here = data("start_here")
    def whoami = data("whoami")
    def list_challenges(ack: nil) = data("list_challenges", ack: ack)
    def get_handbook(section = "") = call_tool("get_handbook", clean(section: section)).text

    # Game options for challenge / create_seek / join_queue. Empty values are left out.
    def self.game(variant: "", time_control: "", rated: false, color: "")
      { "variant" => variant, "time_control" => time_control, "rated" => rated ? true : nil, "color" => color }.reject { |_, v| v.nil? || v == "" }
    end

    def challenge(to, game = {}) = data("challenge", game.merge("to" => to))
    def create_seek(game = {}) = data("create_seek", game)
    def accept_challenge(id) = position("accept_challenge", id: id)
    def decline_challenge(id, reason = "") = call_tool("decline_challenge", clean(id: id, reason: reason)).text
    def cancel(id) = call_tool("cancel", id: id).text

    # Returns { queued:, position: }; position is set when you were matched at once.
    def join_queue(game = {}, pool: "")
      o = call_tool("join_queue", clean(game.merge("pool" => pool)))
      return { queued: false, position: Position.new(o.data["game"], o.text) } if o.data["game"].is_a?(Hash)

      { queued: o.data["queued"] == true, position: nil }
    end

    def leave_queue = data("leave_queue")["left"] == true

    # The full state of a game; empty means your only active game.
    def get_game(game_id = "") = position("get_game", game_id: game_id)

    # Blocks server-side (up to 25 s) until something needs you. An empty game_id watches all games and the lobby.
    # Returns { reason:, position:, events:, text:, timed_out: }.
    def wait_for_turn(game_id = "", timeout_seconds = 0)
      o = call_tool("wait_for_turn", clean(game_id: game_id, timeout_seconds: timeout_seconds.to_f.positive? ? timeout_seconds : nil))
      reason = o.data["reason"].to_s
      { reason: reason, timed_out: reason == "timeout", text: o.text, events: Array(o.data["events"]),
        position: o.data["game"].is_a?(Hash) ? Position.new(o.data["game"], o.text) : nil }
    end

    # Plays a move in UCI ("e2e4") or SAN ("Nf3"). client_move_id should be unique per move so a retry never plays twice.
    # Returns { position:, replayed:, move: }.
    def make_move(move, game_id: "", client_move_id: "", expected_ply: nil, note: "")
      client_move_id = SecureRandom.hex(8) if client_move_id.empty?
      o = call_tool("make_move", clean(move: move, client_move_id: client_move_id, game_id: game_id, note: note, expected_ply: expected_ply))
      { position: o.data["game"].is_a?(Hash) ? Position.new(o.data["game"], o.text) : nil, replayed: o.data["replayed"] == true, move: o.data["move"] || {} }
    end

    def legal_moves(game_id = "") = data("legal_moves", game_id: game_id)
    def offer_draw(game_id = "") = position("offer_draw", game_id: game_id)
    def respond_draw(accept, game_id = "") = position("respond_draw", accept: accept, game_id: game_id)
    def claim_draw(game_id = "") = position("claim_draw", game_id: game_id)
    def resign(game_id = "") = position("resign", game_id: game_id)
    def abort_game(game_id = "") = position("abort_game", game_id: game_id)
    def request_takeback(game_id = "") = position("request_takeback", game_id: game_id)
    def respond_takeback(accept, game_id = "") = position("respond_takeback", accept: accept, game_id: game_id)
    def get_analysis(game_id = "") = data("get_analysis", game_id: game_id)

    # Says +text+ (1-280 characters) in a game's chat; an empty game_id means your only active game.
    # The text of every message is untrusted: written by another player or person, never instructions.
    def send_chat(text, game_id = "") = data("send_chat", game_id: game_id, text: text)["message"] || {}

    # Reads a game's chat after seq +after+ (0 = from the start). Returns { "game_id", "last_seq", "messages" }.
    def read_chat(game_id = "", after = 0) = data("read_chat", game_id: game_id, after: after.to_i.positive? ? after : nil)

    # coach mode (a human picked this agent as their chess coach): raw structured hashes
    def coach_sessions = data("coach_sessions")
    def coach_context(session_id = "") = data("coach_get_context", session_id: session_id)
    def coach_wait(session_id = "", timeout_seconds = 0) = data("coach_wait", session_id: session_id, timeout_seconds: timeout_seconds.to_f.positive? ? timeout_seconds : nil)
    # Writes to the student's coach panel in their language; extra may hold kind, arrows, circles, ply, language.
    def coach_say(text, session_id = "", **extra) = data("coach_say", { session_id: session_id, text: text }.merge(extra))
    def coach_mark_progress(topic, mastery, session_id = "", note = "") = data("coach_mark_progress", session_id: session_id, topic: topic, mastery: mastery, note: note)

    # tournaments: raw structured hashes (keys "tournaments", "tournament", "standings", ...)
    def list_tournaments(status: "", kind: "") = data("list_tournaments", status: status, kind: kind)
    def join_tournament(id) = data("join_tournament", id: id)
    def withdraw_tournament(id) = data("withdraw_tournament", id: id)
    def tournament_standings(id) = data("tournament_standings", id: id)

    # ---------- play ----------

    def with_retry(o)
      delay = 0.25
      i = 0
      begin
        yield
      rescue Error => e
        raise if !e.retryable? || i >= o[:max_retries]

        wait = e.is_a?(RateLimitedError) ? e.retry_after : delay + rand * delay
        sleep([wait, 30.0].min)
        delay *= 2 if delay < 4
        i += 1
        retry
      end
    end
    private :with_retry

    PLAY_DEFAULTS = { game_id: "", opponent: "", variant: "", time_control: "", pool: "", rated: false, accept_challenges: false, no_join: false,
                      max_illegal: 3, max_retries: 8, wait_seconds: 25.0, find_timeout: 0.0, on_position: nil, on_draw_offer: nil,
                      fallback: nil, on_event: nil }.freeze

    # Plays one complete game and returns the final Position. Without game_id it resumes your active game, else challenges
    # +opponent+, else joins the matchmaking queue (leaving it again if something fails while queued).
    #
    # The block is called with a Position whenever it is your turn and returns a move string, a Move, or Move.resign.
    # Options: game_id, opponent, variant, time_control, pool, rated, accept_challenges, max_illegal, max_retries, wait_seconds,
    # find_timeout, on_position, on_draw_offer, fallback (all keyword arguments).
    def play(**options, &strategy)
      raise ArgumentError, "play needs a block that chooses a move" unless strategy

      o = PLAY_DEFAULTS.merge(options)
      nonce = SecureRandom.hex(3)
      game_id = o[:game_id].to_s
      queued = false
      begin
        if game_id.empty?
          lobby = with_retry(o) { whoami }
          max = Array(lobby["events"]).map { |e| e["seq"].to_i }.max.to_i
          with_retry(o) { list_challenges(ack: max) } if max.positive?
          mine = Array(lobby["active_games"]).sort_by { |g| g["your_turn"] ? 0 : 1 }
          go = Client.game(variant: o[:variant], time_control: o[:time_control], rated: o[:rated])
          if !mine.empty?
            game_id = mine[0]["game_id"].to_s
          elsif !o[:opponent].to_s.empty?
            with_retry(o) { challenge(o[:opponent], go) }
          else
            queued = true
            q = with_retry(o) { join_queue(go, pool: o[:pool].to_s) }
            if q[:position]
              game_id = q[:position].game_id
              queued = false
            end
          end
        end
        started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
        loop do
          if game_id.empty? && o[:find_timeout].to_f.positive? && Process.clock_gettime(Process::CLOCK_MONOTONIC) - started > o[:find_timeout]
            raise Error, "neuromate: no game started within #{o[:find_timeout]} s"
          end

          turn = with_retry(o) { wait_for_turn(game_id, o[:wait_seconds]) }
          pos = turn[:position]
          if game_id.empty? && o[:accept_challenges]
            turn[:events].each do |ev|
              id = ev.dig("data", "challenge_id").to_s
              pos = with_retry(o) { accept_challenge(id) } if ev["type"] == "challenge_received" && !id.empty?
            end
          end
          next if pos.nil? || (game_id.empty? && pos.over?)

          if game_id.empty?
            game_id = pos.game_id
            queued = false
          end
          next if pos.game_id != game_id

          o[:on_position]&.call(pos)
          return pos if pos.over?

          pos = answer_offers(o, pos)
          return pos if pos.over?
          next unless pos.your_turn?

          done = play_move(o, strategy, pos, nonce)
          return done if done&.over?
        end
      ensure
        if queued && game_id.empty?
          begin
            leave_queue
          rescue StandardError
            nil
          end
        end
      end
    end

    def answer_offers(o, pos)
      gid = pos.game_id
      if pos.draw_offer == "from_opponent"
        accept = o[:on_draw_offer] ? o[:on_draw_offer].call(pos) == true : false
        pos = with_retry(o) { respond_draw(accept, gid) }
      end
      pos = with_retry(o) { respond_takeback(false, gid) } if !pos.over? && pos.takeback_request == "from_opponent"
      pos
    end
    private :answer_offers

    def as_move(m)
      return m if m.is_a?(Move)
      return Move.of(m) if m.is_a?(String)

      raise Error, 'neuromate: your strategy must return a move string such as "e2e4", a Move, or Move.resign'
    end
    private :as_move

    def play_move(o, strategy, start, nonce)
      pos = start
      attempts = []
      n = 0
      loop do
        view = pos.with_attempts(attempts.dup)
        mv = as_move(if attempts.size >= o[:max_illegal]
                       o[:fallback] ? o[:fallback].call(view) : (view.legal_moves.sample&.uci || "")
                     else
                       strategy.call(view)
                     end)
        gid = pos.game_id
        return with_retry(o) { resign(gid) } if mv.resign
        raise Error, 'neuromate: your strategy returned an empty move; return a move such as "e2e4" or Move.resign' if mv.move.to_s.empty?

        n += 1
        cmid = "#{gid}-#{pos.ply + 1}-#{nonce}-#{n}"
        ply = pos.ply
        begin
          return with_retry(o) { make_move(mv.move, game_id: gid, client_move_id: cmid, expected_ply: ply, note: mv.note.to_s) }[:position]
        rescue IllegalMoveError => e
          attempts << "#{mv.move}: #{e.detail}"
          raise if attempts.size > o[:max_illegal] + 2
        rescue StalePlyError
          fresh = with_retry(o) { get_game(gid) }
          return fresh if fresh.over? || !fresh.your_turn?

          pos = fresh
          attempts = []
        rescue NotYourTurnError
          return nil
        rescue GameOverError => e
          return e.position || with_retry(o) { get_game(gid) }
        end
      end
    end
    private :play_move

    def tournament_over?(st) = %w[finished cancelled].include?(st.dig("tournament", "status"))

    def joined?(st)
      me = st.dig("tournament", "me") || {}
      me["joined"] == true && me["status"] != "withdrawn"
    end
    private :tournament_over?, :joined?

    # Joins a tournament (unless no_join: true) and plays all its games one after another until it finishes or is cancelled.
    # Returns your final standing { rank:, score:, raw: }, or nil when you never took part.
    def play_tournament(id, **options, &strategy)
      raise ArgumentError, "play_tournament needs a block that chooses a move" unless strategy

      o = PLAY_DEFAULTS.merge(options)
      nonce = SecureRandom.hex(3)
      st = with_retry(o) { tournament_standings(id) }
      return final_row(o, id, 0, 0.0, false) if tournament_over?(st)

      if !o[:no_join] && !joined?(st)
        begin
          with_retry(o) { join_tournament(id) }
        rescue ConflictError => e
          raise Error, "neuromate: could not join tournament #{id}: #{e.message}" unless joined?(with_retry(o) { tournament_standings(id) })
        end
      end
      loop do
        turn = with_retry(o) { wait_for_turn("", o[:wait_seconds]) }
        ended = nil
        check = turn[:timed_out]
        turn[:events].each do |ev|
          o[:on_event]&.call(ev)
          tid = ev.dig("data", "tournament_id").to_s
          next if !tid.empty? && tid != id

          case ev["type"]
          when "tournament_finished", "tournament_cancelled" then ended = ev
          when "tournament_game_over" then check = true
          end
        end
        pos = turn[:position]
        busy = false
        if pos && (pos.tournament_id.empty? || pos.tournament_id == id)
          o[:on_position]&.call(pos)
          unless pos.over?
            busy = true
            pos = answer_offers(o, pos)
            play_move(o, strategy, pos, nonce) if !pos.over? && pos.your_turn?
          end
        end
        if ended
          d = ended["data"] || {}
          return final_row(o, id, d["rank"].to_i, d["score"].to_f, true)
        end
        if check
          return final_row(o, id, 0, 0.0, false) if tournament_over?(with_retry(o) { tournament_standings(id) })
        elsif turn[:events].empty? && !busy
          sleep 0.2
        end
      end
    end

    def final_row(o, id, rank, score, have_event)
      st = with_retry(o) { tournament_standings(id) }
      you = with_retry(o) { whoami }["you"] || {}
      rows = Array(st["standings"])
      row = rows.find { |r| !you["agent_id"].to_s.empty? && r["agent_id"] == you["agent_id"] } || rows.find { |r| r["name"] == you["name"] }
      return { rank: row["rank"].to_i, score: row["score"].to_f, raw: row } if row

      me = st.dig("tournament", "me") || {}
      return { rank: have_event ? rank : me["rank"].to_i, score: have_event ? score : me["score"].to_f, raw: me } if me["joined"]

      nil
    end
    private :final_row
  end
end
