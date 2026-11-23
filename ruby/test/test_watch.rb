# frozen_string_literal: true

# Watcher tests against the recordings in ../testdata, through the scripted fake server of test_client.rb.
# Run: ruby -Ilib test/test_watch.rb
require_relative "test_client"

class WatchTest < Minitest::Test
  ON_RULES = { "enabled" => true, "speeds" => ["blitz"], "variants" => ["standard"], "rated" => "any", "ruleset" => "any",
               "opponent_kinds" => [], "max_concurrent" => 1, "decline_others" => false }.freeze

  def standby(**rules)
    r = JSON.parse(JSON.generate(fixture("standby_on")["response"]["result"]))
    r["structuredContent"]["rules"] = ON_RULES.merge(rules.transform_keys(&:to_s))
    r
  end

  def challenge = result("wait_challenge_received")
  def your_turn = wait_result("your_turn", "get_game_start")
  def game_over = wait_result("game_over", "resign")

  def run_watcher(fake, **opts)
    logs = []
    w = Neuromate::Watcher.new(fake.client, ->(_p) { "e4" }, log: ->(m) { logs << m }, heartbeat_every: 0.05, busy_poll: 0.02, **opts)
    t = Thread.new { w.run }
    deadline = Time.now + 10
    sleep 0.02 until yield(w, logs) || Time.now > deadline
    w.stop
    assert t.join(5), "the watcher did not stop"
    [w, logs]
  end

  def test_rules_match_the_shared_vectors
    cases = JSON.parse(File.read(File.join(TESTDATA, "auto_accept_cases.json")))
    assert_operator cases.size, :>=, 15
    cases.each do |c|
      got = Neuromate::Rules.from_hash(c["rules"]).evaluate(c["challenge"], c["playing"]).action
      assert_equal c["expect"], got, c["name"]
    end
  end

  def test_speed_and_challenge_info
    assert_equal %w[bullet blitz rapid classical correspondence untimed], ["1+0", "3+2", "10+0", "30+0", "2d", "untimed"].map { |x| Neuromate.speed_of(nil, x) }
    assert_equal "blitz", Neuromate.speed_of({ "kind" => "realtime", "initial_ms" => 180_000, "increment_ms" => 2000 })
    info = Neuromate.info_from_event(challenge["structuredContent"]["events"][0])
    assert_equal ["blitz", "agent", "standard", false], info.values_at("speed", "from_kind", "variant", "rated")
    busy = result("list_challenges_busy")["structuredContent"]
    assert_equal "rapid", Neuromate.info_from_challenge(busy["incoming_challenges"][0])["speed"]
  end

  def test_standby_parses_the_owners_rules
    d = Fake.new.on("standby", standby(speeds: ["bullet"])).client.standby("x")
    assert d.on_duty
    assert_equal ["bullet"], d.rules.speeds
    assert_equal 30, d.heartbeat_every_seconds
  end

  def test_accepts_a_matching_challenge_and_plays_the_game
    f = Fake.new.on("standby", standby).on("whoami", result("whoami_idle")).on("list_challenges", result("list_challenges_empty"))
        .on("wait_for_turn", challenge, your_turn, game_over, result("wait_timeout"))
        .on("accept_challenge", result("get_game_start")).on("make_move", result("move_ok"))
    w, = run_watcher(f) { |x, _| x.played >= 1 }
    assert_equal 1, w.played
    assert_equal 1, w.accepted
    assert_equal "standby", f.names[0]
    assert_includes f.names, "accept_challenge"
    assert_includes f.names, "make_move"
    assert_equal "ch_c4v3a4vr", f.args[f.names.index("accept_challenge")]["id"]
    assert_equal "off", f.args.last["action"], "a clean shutdown ends the duty"
    assert_match(/\Aneuromate-rb\//, f.args[0]["client"])
  end

  def test_heartbeats_keep_coming_while_idle
    f = Fake.new.on("standby", standby).on("whoami", result("whoami_idle")).on("list_challenges", result("list_challenges_empty"))
        .on("wait_for_turn", result("wait_timeout"))
    run_watcher(f) { |_, _| f.names.count("standby") >= 4 }
    assert_operator f.names.count("standby"), :>=, 4
  end

  def test_a_challenge_outside_the_rules_is_left_or_declined
    script = lambda do |**rules|
      Fake.new.on("standby", standby(speeds: ["bullet"], **rules)).on("whoami", result("whoami_idle")).on("list_challenges", result("list_challenges_empty"))
          .on("wait_for_turn", challenge, result("wait_timeout")).on("decline_challenge", result("cancel")).on("accept_challenge", result("get_game_start"))
    end
    f = script.call
    run_watcher(f) { |_, logs| logs.any? { |m| m.include?("left for the owner") } }
    refute_includes f.names, "accept_challenge"
    refute_includes f.names, "decline_challenge"
    f = script.call(decline_others: true)
    run_watcher(f) { |_, _| f.names.include?("decline_challenge") }
    refute_includes f.names, "accept_challenge"
    assert_includes f.args[f.names.index("decline_challenge")]["reason"], "blitz"
  end

  def test_local_rules_override_the_owners
    f = Fake.new.on("standby", standby(enabled: false)).on("whoami", result("whoami_idle")).on("list_challenges", result("list_challenges_empty"))
        .on("wait_for_turn", challenge, your_turn, game_over, result("wait_timeout")).on("accept_challenge", result("get_game_start")).on("make_move", result("move_ok"))
    w, = run_watcher(f, rules: Neuromate::Rules.new(enabled: true)) { |x, _| x.played >= 1 }
    assert_equal 1, w.accepted
  end

  def test_resumes_a_running_game_after_a_restart
    gid = result("get_game_start")["structuredContent"]["game"]["game_id"]
    busy = JSON.parse(JSON.generate(result("whoami_idle")))
    busy["structuredContent"]["active_games"] = [{ "game_id" => gid, "opponent" => "Bob", "color" => "white", "your_turn" => true, "ply" => 0, "variant" => "standard" }]
    f = Fake.new.on("standby", standby).on("whoami", busy).on("list_challenges", result("list_challenges_empty"))
        .on("wait_for_turn", your_turn, game_over, result("wait_timeout")).on("make_move", result("move_ok"))
    w, = run_watcher(f) { |x, _| x.played >= 1 }
    assert_equal 1, w.played
    refute_includes f.names, "accept_challenge"
  end

  def test_a_crashing_handler_does_not_kill_the_watcher
    f = Fake.new.on("standby", standby).on("whoami", result("whoami_idle")).on("list_challenges", result("list_challenges_empty"))
        .on("wait_for_turn", challenge, your_turn, game_over, result("wait_timeout")).on("accept_challenge", result("get_game_start")).on("make_move", result("move_ok"))
    logs = []
    w = Neuromate::Watcher.new(f.client, ->(_p) { raise "model crashed" }, log: ->(m) { logs << m }, heartbeat_every: 0.05, busy_poll: 0.02)
    t = Thread.new { w.run }
    deadline = Time.now + 8
    sleep 0.02 until f.names.include?("make_move") || Time.now > deadline
    assert t.alive?, "the watcher itself must survive"
    w.stop
    t.join(5)
    assert(logs.any? { |m| m.include?("handler error") })
  end
end
