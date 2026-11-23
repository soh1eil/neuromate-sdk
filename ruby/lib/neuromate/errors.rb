# frozen_string_literal: true

module Neuromate
  # Base of every error the SDK raises. #retryable? says whether repeating the call may help.
  class Error < StandardError
    def retryable? = false
  end

  # The request never got an answer (connection refused, timeout, DNS).
  class TransportError < Error
    def retryable? = true
  end

  # An unexpected HTTP status.
  class HTTPError < Error
    attr_reader :status

    def initialize(status, body)
      @status = status
      super("HTTP #{status}: #{body}")
    end
  end

  # HTTP 401: the API key is missing, wrong or revoked.
  class AuthError < Error
    attr_reader :code, :hint

    def initialize(message, code: "", hint: "")
      @code = code
      @hint = hint
      super(message)
    end
  end

  # HTTP 5xx or a platform INTERNAL ERROR.
  class ServerError < Error
    def retryable? = true
  end

  # Too many requests; wait +retry_after+ seconds before retrying.
  class RateLimitedError < Error
    attr_reader :retry_after

    def initialize(message, retry_after: 1.0)
      @retry_after = retry_after
      super(message)
    end

    def retryable? = true
  end

  # A JSON-RPC level error (unknown tool, invalid params, unreadable reply).
  class ProtocolError < Error
    attr_reader :rpc_code, :data

    def initialize(message, rpc_code: 0, data: nil)
      @rpc_code = rpc_code
      @data = data
      super(message)
    end
  end

  # A tool ran and said no. #message is the server's complete teaching text; #code is the stable machine code
  # (illegal_move, not_your_turn, stale_ply, game_over, model_not_reported, invalid_params, ...).
  class ToolError < Error
    attr_reader :tool, :code, :detail, :hint, :legal_moves, :position, :context

    def initialize(tool:, code:, detail:, hint:, legal_moves:, position:, text:, context:)
      @tool = tool
      @code = code
      @detail = detail
      @hint = hint
      @legal_moves = legal_moves
      @position = position
      @context = context
      super(text.to_s.empty? ? detail : text)
    end

    def self.from(tool, text, structured)
      err = structured["error"].is_a?(Hash) ? structured["error"] : {}
      code = err["code"].to_s
      msg = err["message"].to_s
      msg = text.to_s.strip if msg.empty?
      hint = err["hint"].to_s
      hint = err["what_to_do"].to_s if hint.empty?
      legal = Array(err["legal_moves"]).grep(String)
      ctx = err.reject { |k, _| %w[code message hint what_to_do legal_moves].include?(k) }
      pos = structured["game"].is_a?(Hash) ? Position.new(structured["game"], text) : nil
      cls = { "illegal_move" => IllegalMoveError, "not_your_turn" => NotYourTurnError, "stale_ply" => StalePlyError,
              "game_over" => GameOverError, "model_not_reported" => ModelNotReportedError, "conflict" => ConflictError }.fetch(code, self)
      cls.new(tool: tool, code: code, detail: msg, hint: hint, legal_moves: legal, position: pos, text: text, context: ctx)
    end
  end

  class IllegalMoveError < ToolError; end
  class NotYourTurnError < ToolError; end
  # expected_ply no longer matches: re-read the game.
  class StalePlyError < ToolError; end
  class GameOverError < ToolError; end
  # report_model has not been called in this session (the client does it for you when you give it a model).
  class ModelNotReportedError < ToolError; end
  class ConflictError < ToolError; end
end
