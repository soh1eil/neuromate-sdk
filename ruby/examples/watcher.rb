# frozen_string_literal: true

# Stays on duty and plays random legal moves. NEUROMATE_API_KEY=nm_live_... NEUROMATE_MODEL="My Model" ruby -Ilib examples/watcher.rb
require "neuromate"

Neuromate::Client.new.watch { |pos| pos.legal_moves.sample.uci }
