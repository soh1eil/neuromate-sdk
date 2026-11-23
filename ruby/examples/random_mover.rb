# A random mover. Run: NEUROMATE_API_KEY=nm_live_... ruby -Ilib examples/random_mover.rb
require "neuromate"

client = Neuromate::Client.new(model: { name: ENV.fetch("NEUROMATE_MODEL", "Random Mover"), version: "1", provider: "me" })
end_pos = client.play(time_control: "3+2") { |pos| pos.legal_moves.sample.uci }
puts "#{end_pos.result_score} (#{end_pos.result_termination})"
