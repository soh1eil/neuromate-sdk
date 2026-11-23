# frozen_string_literal: true

# Unit tests against the recordings in ../testdata, through a scripted fake server (no network).
# Run: ruby -Ilib test/test_client.rb
require "minitest/autorun"
require "json"
require "neuromate"

TESTDATA = File.expand_path("../../testdata", __dir__)

def fixture(name) = JSON.parse(File.read(File.join(TESTDATA, "#{name}.json")))
def result(name) = fixture(name)["response"]["result"]

def wait_result(reason, game_from)
  { "content" => [{ "type" => "text", "text" => "WAIT" }],
    "structuredContent" => { "reason" => reason, "events" => [], "game" => result(game_from)["structuredContent"]["game"] } }
end

class Fake
  attr_reader :names, :args
  attr_accessor :forced, :content_type

  def initialize = (@script = {}; @names = []; @args = []; @forced = nil; @content_type = "application/json")

  def on(tool, *results) = (@script[tool] = results.dup; self)

  def client(key: "nm_live_test", model: nil)
    Neuromate::Client.new(api_key: key, model: model, transport: method(:call))
  end

  def call(_url, _headers, body, _timeout)
    return @forced if @forced

    req = JSON.parse(body)
    return { status: 202, headers: {}, body: "" } if req["method"] == "notifications/initialized"

    res = if req["method"] == "initialize"
            { "serverInfo" => { "name" => "fake", "version" => "1" } }
          else
            name = req["params"]["name"]
            @names << name
            @args << req["params"]["arguments"]
            q = @script.fetch(name) { raise "no scripted reply for #{name}" }
            q.size > 1 ? q.shift : q[0]
          end
    json = JSON.generate({ "jsonrpc" => "2.0", "id" => req["id"], "result" => res })
    json = "event: message\ndata: #{json}\n\n" if @content_type.include?("event-stream")
    { status: 200, headers: { "content-type" => @content_type }, body: json }
  end
end

class ClientTest < Minitest::Test
  def test_queue_then_play_until_game_over
    f = Fake.new.on("whoami", result("whoami_idle")).on("join_queue", result("join_queue_waiting"))
        .on("wait_for_turn", wait_result("your_turn", "get_game_start"), wait_result("game_over", "get_game_finished"))
        .on("make_move", result("move_ok"))
    seen = 0
    fin = f.client.play(time_control: "3+0") { |_p| seen += 1; Neuromate::Move.of("e4", "centre") }
    assert fin.over?
    assert_equal 1, seen
    assert_equal %w[whoami join_queue wait_for_turn make_move wait_for_turn], f.names
    mv = f.args[3]
    assert_equal "e4", mv["move"]
    assert_equal "centre", mv["note"]
    assert_equal 0, mv["expected_ply"]
    assert mv["client_move_id"].start_with?(fin.game_id)
    assert_equal({ "time_control" => "3+0" }, f.args[1])
  end

  def test_illegal_move_is_typed_and_carries_code
    f = Fake.new.on("make_move", result("err_illegal_move"))
    e = assert_raises(Neuromate::IllegalMoveError) { f.client.make_move("e2e5", client_move_id: "x2") }
    assert_equal "illegal_move", e.code
    assert_equal 20, e.legal_moves.size
    assert_includes e.legal_moves, "e2e4"
    refute_nil e.position
    assert_includes e.message, "ERROR (illegal_move)"
  end

  def test_rejected_moves_are_fed_back_then_fallback_is_used
    f = Fake.new.on("wait_for_turn", wait_result("your_turn", "get_game_start"), wait_result("game_over", "get_game_finished"))
        .on("make_move", result("err_illegal_move"), result("err_illegal_move"), result("move_ok"))
    seen = []
    f.client.play(game_id: "g76p6aeov4", max_illegal: 1) { |p| seen << p.illegal_attempts.size; "e2e5" }
    assert_equal [0], seen
    assert_equal 3, f.names.count("make_move")
  end

  def test_model_is_reported_before_the_first_call
    f = Fake.new.on("report_model", result("report_model")).on("join_queue", result("join_queue_waiting"))
    f.client(model: { name: "Claude Opus 5", version: "2026-06", provider: "Anthropic" }).join_queue
    assert_equal %w[report_model join_queue], f.names
    assert_equal "Claude Opus 5", f.args[0]["name"]
    assert_equal "Anthropic", f.args[0]["provider"]
  end

  def test_model_not_reported_is_repaired_once
    f = Fake.new.on("report_model", result("report_model")).on("join_queue", result("err_model_not_reported"), result("join_queue_waiting"))
    f.client(model: "Claude Opus 5").join_queue
    assert_equal %w[report_model join_queue report_model join_queue], f.names
  end

  def test_model_not_reported_without_a_model_explains_how_to_fix
    f = Fake.new.on("join_queue", result("err_model_not_reported"))
    e = assert_raises(Neuromate::ToolError) { f.client.join_queue }
    assert_equal "model_not_reported", e.code
    assert_includes e.detail, "Client.new(model:"
  end

  def test_unauthorized_is_auth_error
    f = Fake.new
    f.forced = { status: 401, headers: {}, body: JSON.generate(fixture("http_401")["response"]) }
    e = assert_raises(Neuromate::AuthError) { f.client.whoami }
    assert_equal "unauthorized", e.code
    refute e.retryable?
    assert_includes e.message, "Authorization: Bearer"
  end

  def test_server_errors_are_retryable
    f = Fake.new
    f.forced = { status: 503, headers: {}, body: "down" }
    assert assert_raises(Neuromate::ServerError) { f.client.whoami }.retryable?
  end

  def test_server_sent_events_reply_is_unwrapped
    f = Fake.new.on("whoami", result("whoami_idle"))
    f.content_type = "text/event-stream"
    assert_equal "Alice", f.client.whoami["you"]["name"]
  end

  def test_key_is_never_printed
    refute_includes Fake.new.client(key: "nm_live_SECRETSECRETSECRET").inspect, "SECRETSECRET"
  end

  def test_tournaments_come_back_as_hashes
    f = Fake.new.on("list_tournaments", result("tournaments_list"))
    d = f.client.list_tournaments(status: "registering,running", kind: "swiss")
    assert_equal "t_k3m9x2qa", d["tournaments"][0]["id"]
    assert_equal({ "status" => "registering,running", "kind" => "swiss" }, f.args[0])
  end

  def test_position_parses_clocks_and_legal_moves
    p = Neuromate::Position.new(result("get_game_start")["structuredContent"]["game"])
    assert_equal 20, p.legal_moves.size
    assert_equal "white", p.color
    assert p.your_turn?
    assert_operator p.seconds_for_this_move, :>, 80
  end

  def test_chat_is_untrusted_data_and_strict_rules_are_parsed
    f = Fake.new.on("send_chat", result("send_chat")).on("read_chat", result("read_chat")).on("get_game", result("get_game_strict"))
    c = f.client
    m = c.send_chat("good luck!", "gzcffjrmdb")
    assert_equal ["good luck!", "agent"], [m["text"], m["author"]["kind"]]
    assert_equal "good luck!", f.args[0]["text"]
    log = c.read_chat("gzcffjrmdb")
    assert_equal 1, log["last_seq"]
    assert log["messages"][0]["untrusted"]
    refute f.args[1].key?("after")
    p = c.get_game
    assert p.strict?
    refute p.hints_allowed?
  end
end
