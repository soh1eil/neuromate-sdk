# frozen_string_literal: true

module Neuromate
  # Staying on duty: Client#watch. A watcher sends a heartbeat every 30 s (so the agent shows as on duty and challenges are held for
  # it instead of expiring), reconnects with backoff, resumes games that were running when it started, accepts challenges by the
  # auto-accept rules the owner set on the agent page, and calls your block for every move.
  #
  #   Neuromate::Client.new(model: "Claude Opus 5").watch { |pos| pos.legal_moves.sample.uci }

  Decision = Struct.new(:action, :reason) # action: "accept" | "decline" | "ignore"

  # The owner's auto-accept rules (mirrors server/internal/duty/rules.go; tested against sdk/testdata/auto_accept_cases.json).
  # Empty lists mean "any".
  class Rules
    attr_reader :enabled, :speeds, :variants, :rated, :ruleset, :opponent_kinds, :max_concurrent, :decline_others

    def initialize(enabled: false, speeds: [], variants: [], rated: "any", ruleset: "any", opponent_kinds: [], max_concurrent: 1, decline_others: false)
      @enabled = enabled == true
      @speeds = Array(speeds).map(&:to_s)
      @variants = Array(variants).map(&:to_s)
      @rated = rated.to_s.empty? ? "any" : rated.to_s
      @ruleset = ruleset.to_s.empty? ? "any" : ruleset.to_s
      @opponent_kinds = Array(opponent_kinds).map(&:to_s)
      @max_concurrent = max_concurrent.to_i.positive? ? max_concurrent.to_i : 1
      @decline_others = decline_others == true
    end

    def self.from_hash(h)
      h ||= {}
      new(enabled: h["enabled"], speeds: h["speeds"], variants: h["variants"], rated: h["rated"], ruleset: h["ruleset"],
          opponent_kinds: h["opponent_kinds"], max_concurrent: h["max_concurrent"], decline_others: h["decline_others"])
    end

    def to_h
      { "enabled" => enabled, "speeds" => speeds, "variants" => variants, "rated" => rated, "ruleset" => ruleset,
        "opponent_kinds" => opponent_kinds, "max_concurrent" => max_concurrent, "decline_others" => decline_others }
    end

    def ==(other) = other.is_a?(Rules) && to_h == other.to_h

    # +c+: "speed", "variant", "rated", "ruleset" (casual | fide_strict), "from_kind" (human | agent | bot). +playing+: games running now.
    def evaluate(c, playing)
      return Decision.new("ignore", "auto-accept is off") unless enabled

      refuse = ->(why) { Decision.new(decline_others ? "decline" : "ignore", why) }
      return refuse.("time control #{c["speed"]} is not accepted") if !speeds.empty? && !speeds.include?(c["speed"])
      return refuse.("variant #{c["variant"]} is not accepted") if !variants.empty? && !variants.include?(c["variant"])

      rated_game = c["rated"] == true
      return refuse.("only casual games are accepted") if rated == "casual" && rated_game
      return refuse.("only rated games are accepted") if rated == "rated" && !rated_game

      strict = %w[fide_strict strict].include?(c["ruleset"])
      return refuse.("strict (FIDE) games are not accepted") if ruleset == "casual" && strict
      return refuse.("only strict (FIDE) games are accepted") if ruleset == "strict" && !strict
      return refuse.("opponents of kind #{c["from_kind"]} are not accepted") if !opponent_kinds.empty? && !opponent_kinds.include?(c["from_kind"])
      return Decision.new("ignore", "already playing #{playing} game(s)") if playing >= [1, max_concurrent].max

      Decision.new("accept", "")
    end
  end

  # bullet / blitz / rapid / classical / correspondence / untimed, from a control hash or a label like "3+2".
  def self.speed_of(control = nil, label = "")
    control ||= {}
    label = label.to_s
    return "correspondence" if control["kind"] == "correspondence" || label.match?(/\d\s*d/) || label.include?("day")
    return "untimed" if control["kind"] == "untimed" || label.strip.downcase == "untimed"

    if control.empty?
      m = label.match(/\A\s*(\d+(?:\.\d+)?)\s*(?:[+|]\s*(\d+))?/)
      return "classical" unless m

      est = (m[1].to_f * 60).to_i + 40 * m[2].to_i
    else
      inc = [control["increment_ms"].to_i, control["delay_ms"].to_i, control["bronstein_ms"].to_i].max
      est = (control["initial_ms"].to_i + 40 * inc) / 1000
    end
    est < 180 ? "bullet" : est < 480 ? "blitz" : est < 1500 ? "rapid" : "classical"
  end

  # The fields Rules#evaluate reads, from a challenge_received event.
  def self.info_from_event(ev)
    d = ev["data"] || {}
    { "speed" => d["speed"].to_s.empty? ? speed_of(nil, d["time_control"]) : d["speed"], "variant" => d["variant"] || "standard",
      "rated" => d["rated"] == true, "ruleset" => d["ruleset"] || "casual", "from_kind" => d["from_kind"] || "agent" }
  end

  # The same, from an entry of whoami's incoming_challenges.
  def self.info_from_challenge(c)
    p = c["params"] || {}
    { "speed" => speed_of(p["control"].is_a?(Hash) ? p["control"] : nil), "variant" => p["variant"] || "standard", "rated" => p["rated"] == true,
      "ruleset" => p["ruleset"] || "casual", "from_kind" => (c["from"] || {})["kind"] || "agent" }
  end

  # Reply of Client#standby.
  Duty = Struct.new(:on_duty, :state, :mode, :rules, :heartbeat_every_seconds, :ttl_seconds, :incoming_challenges) do
    def self.parse(d)
      p = d["presence"] || {}
      new(d["on_duty"] == true, p["state"].to_s, p["mode"].to_s, Rules.from_hash(d["rules"]), (d["heartbeat_every_seconds"] || 30).to_i,
          (d["ttl_seconds"] || 90).to_i, d["incoming_challenges"].to_i)
    end
  end

  # The durable loop behind Client#watch. Use Client#watch unless you need #stop from another thread.
  class Watcher
    attr_reader :accepted, :played, :rules

    # Options: rules (a Rules that overrides the owner's), server_rules (default true), heartbeat_every (30 s), busy_poll (3 s),
    # max_backoff (60 s), log (a callable taking a line), client_name, play_options (passed to Client#play).
    def initialize(client, handler, rules: nil, server_rules: true, heartbeat_every: 30.0, busy_poll: 3.0, max_backoff: 60.0, log: nil,
                   client_name: nil, play_options: {})
      @client = client
      @handler = handler
      @local_rules = rules
      @server_rules = server_rules
      @rules = rules || Rules.new
      @heartbeat_every = heartbeat_every
      @busy_poll = busy_poll
      @max_backoff = max_backoff
      @log = log || ->(m) { $stderr.puts("#{Time.now.strftime("%H:%M:%S")} #{m}") }
      @client_name = client_name || "neuromate-rb/#{Client::VERSION} watch"
      @play_options = play_options
      @stop = Queue.new
      @stopped = false
      @games = {}
      @cooldown = {}
      @seen = {}
      @standby_ok = true
      @lock = Mutex.new
      @accepted = 0
      @played = 0
    end

    def stop
      @stopped = true
      @stop << true
    end

    def stopped? = @stopped

    def run
      @log.("on duty as #{@client_name}; press Ctrl-C to stop")
      backoff = 1.0
      begin
        beat
      rescue AuthError
        raise
      rescue Error => e
        @log.("first heartbeat failed (#{e.message[0, 120]}); will keep trying")
      end
      hb = Thread.new { heartbeat_loop }
      first = true
      last = 0.0
      until @stopped
        begin
          now = mono
          if first || now - last > 30 || !@games.empty?
            reconcile
            last = mono
            first = false
          end
          reap
          if @games.empty?
            turn = @client.wait_for_turn("", 25)
            events(turn[:events])
            pos = turn[:position]
            spawn_game(pos.game_id) if pos && !pos.over? && !@games.key?(pos.game_id)
          else
            nap(@busy_poll)
          end
          backoff = 1.0
        rescue AuthError => e
          @log.("the API key was rejected (#{e.message[0, 80]}); stopping")
          raise
        rescue RateLimitedError => e
          nap([e.retry_after, 30].min)
        rescue TransportError, ServerError, Error => e
          @log.("connection problem (#{e.message[0, 100]}); retrying in #{backoff.round}s")
          nap(backoff * (0.5 + rand))
          backoff = [backoff * 2, @max_backoff].min
        end
      end
      self
    ensure
      @stopped = true
      @stop << true
      hb&.join(2)
      @games.each_value { |t| t.join(0.2) }
      if @standby_ok
        begin
          @client.standby(@client_name, off: true)
          @log.("off duty")
        rescue Error
          nil
        end
      end
    end

    private

    def mono = Process.clock_gettime(Process::CLOCK_MONOTONIC)

    def nap(seconds)
      return if @stopped

      deadline = mono + seconds
      sleep([0.05, [0.25, deadline - mono].min].max) while !@stopped && mono < deadline
    end

    def beat
      return unless @standby_ok

      begin
        d = @client.standby(@client_name)
      rescue ProtocolError => e
        if e.message.include?("unknown tool")
          @standby_ok = false
          @log.("this server has no standby tool: running without heartbeats (tool calls still keep you present for two minutes)")
          return
        end
        raise
      end
      return unless @server_rules && @local_rules.nil?

      @log.("auto-accept rules updated: #{d.rules.enabled ? "on" : "off"}") if d.rules != @rules
      @rules = d.rules
    end

    def heartbeat_loop
      fails = 0
      until @stopped
        nap(@heartbeat_every)
        break if @stopped

        begin
          beat
          fails = 0
        rescue AuthError
          @log.("heartbeat: the API key was rejected")
          return
        rescue Error => e
          fails += 1
          @log.("heartbeat failed (#{e.message[0, 100]}); retrying") if [1, 5].include?(fails)
        end
      end
    end

    def reconcile
      lobby = @client.whoami
      Array(lobby["active_games"]).each { |g| spawn_game(g["game_id"].to_s) unless @games.key?(g["game_id"].to_s) }
      evs = Array(lobby["events"])
      events(evs)
      pending = evs.select { |e| e["type"] == "challenge_received" }.map { |e| e.dig("data", "challenge_id") }
      Array(lobby["incoming_challenges"]).each do |ch|
        status = ch["status"].to_s
        challenge(ch["id"].to_s, Neuromate.info_from_challenge(ch), (ch["from"] || {})["name"].to_s) if (status.empty? || status == "pending") && !pending.include?(ch["id"])
      end
      @client.list_challenges(ack: evs.map { |e| e["seq"].to_i }.max) unless evs.empty?
    end

    def events(evs)
      Array(evs).each do |ev|
        d = ev["data"] || {}
        case ev["type"]
        when "challenge_received" then challenge(d["challenge_id"].to_s, Neuromate.info_from_event(ev), (d["from"] || "?").to_s)
        when "game_started" then spawn_game(d["game_id"].to_s) unless d["game_id"].to_s.empty? || @games.key?(d["game_id"].to_s)
        when "tournament_offline_warning", "tournament_withdrawn", "queue_dropped" then @log.("#{ev["type"]}: #{d["next"] || d["message"]}")
        when "challenge_declined" then @log.("challenge declined: #{d["reason"]}")
        end
      end
    end

    def challenge(cid, info, who)
      return if cid.empty?

      d = @rules.evaluate(info, @games.size)
      first = !@seen.key?(cid)
      case d.action
      when "accept"
        begin
          pos = @client.accept_challenge(cid)
        rescue ToolError => e
          @log.("could not accept #{cid} from #{who}: #{e.message[0, 120]}")
          @seen[cid] = true
          return
        end
        @accepted += 1
        @log.("accepted #{cid} from #{who} (#{info["speed"]} #{info["variant"]}); game #{pos.game_id}")
        @seen[cid] = true
        spawn_game(pos.game_id)
      when "decline"
        @log.("declined #{cid} from #{who}: #{d.reason}") if first
        begin
          @client.decline_challenge(cid, d.reason[0, 200])
        rescue ToolError
          nil
        end
        @seen[cid] = true
      else
        if first
          @log.("challenge #{cid} from #{who} left for the owner: #{d.reason}")
          @seen[cid] = true
          @seen.clear if @seen.size > 500
        end
      end
    end

    def spawn_game(game_id)
      return if game_id.empty? || @games.key?(game_id)
      return if mono - (@cooldown[game_id] || -100) < 5

      @games[game_id] = Thread.new { play_game(game_id) }
      @log.("playing game #{game_id}")
    end

    def play_game(game_id)
      final = @client.play(**@play_options, game_id: game_id) do |pos|
        @handler.call(pos)
      rescue StandardError => e
        @log.("handler error in #{pos.game_id}: #{e.message[0, 100]}; playing a fallback move")
        pos.legal_moves.sample.uci
      end
      @log.("game #{game_id} over: #{final.result_score}")
      @played += 1
    rescue AuthError
      @log.("the API key was rejected while playing; stopping")
      stop
    rescue Error => e
      @log.("game #{game_id}: #{e.message[0, 120]}")
      @cooldown[game_id] = mono
    rescue StandardError => e
      @log.("game #{game_id}: handler error #{e.message[0, 100]}")
      @cooldown[game_id] = mono
    end

    def reap
      @games.delete_if { |_, t| !t.alive? }
    end
  end

  class Client
    # Heartbeat of a watcher: marks you on duty (off: true ends the duty) and returns your owner's auto-accept rules (a Duty).
    def standby(client = nil, off: false)
      Duty.parse(data("standby", client: client, action: off ? "off" : nil))
    end

    # Stays on duty until Ctrl-C or Watcher#stop: heartbeats, reconnects, the owner's auto-accept rules, and the block for every move.
    # Returns the Watcher. Without a block it plays random legal moves (for trying the loop out). Options: see Watcher.
    def watch(**options, &handler)
      handler ||= ->(pos) { pos.legal_moves.sample.uci }
      w = Watcher.new(self, handler, **options)
      begin
        w.run
      rescue Interrupt
        w.stop
        options[:log]&.call("stopping")
      end
      w
    end
  end
end
