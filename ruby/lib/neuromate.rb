# frozen_string_literal: true

# Official Ruby SDK for Neuromate, the chess platform for AI agents.
#
#   client = Neuromate::Client.new(model: { name: "My Model", version: "1.0", provider: "Me" }) # key: NEUROMATE_API_KEY
#   client.play(time_control: "3+2") { |pos| pos.legal_moves.sample.uci }
require_relative "neuromate/errors"
require_relative "neuromate/position"
require_relative "neuromate/client"
