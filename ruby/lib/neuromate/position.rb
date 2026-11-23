# frozen_string_literal: true

module Neuromate
  LegalMove = Struct.new(:uci, :san)

  # The state of one game: FEN, ASCII board, legal moves (only on your turn) and the clocks (milliseconds, 0 = unknown).
  class Position
    attr_reader :raw, :text, :game_id, :status, :variant, :time_control, :pool, :turn, :fen, :board, :color, :opponent_name,
                :legal_moves, :moves_san, :draw_offer, :takeback_request, :you_ms, :opponent_ms, :running,
                :result_score, :result_winner, :result_termination, :tournament_id, :next_hint, :ply,
                :ruleset, :rules_text, :user_locale, :opponent_locale
    # Rejected moves of this turn ("move: reason"), filled by Client#play.
    attr_accessor :illegal_attempts

    def initialize(raw, text = "")
      @raw = raw
      @text = text.to_s
      s = ->(h, k) { h.is_a?(Hash) && h[k].is_a?(String) ? h[k] : "" }
      n = ->(h, k) { h.is_a?(Hash) && h[k].is_a?(Numeric) ? h[k].to_i : 0 }
      @game_id = s.(raw, "game_id")
      @status = s.(raw, "status")
      @variant = s.(raw, "variant")
      @time_control = s.(raw, "time_control")
      @pool = s.(raw, "pool")
      @rated = raw["rated"] == true
      @turn = s.(raw, "turn")
      @your_turn = raw["your_turn"] == true
      @ply = n.(raw, "ply")
      @fen = s.(raw, "fen")
      @board = s.(raw, "board")
      @color = s.(raw["you"], "color")
      @opponent_name = s.(raw["opponent"], "name")
      @legal_moves = Array(raw["legal_moves"]).map { |m| LegalMove.new(s.(m, "uci"), s.(m, "san")) }
      @moves_san = Array(raw["moves_san"]).grep(String)
      @draw_offer = s.(raw, "draw_offer")
      @takeback_request = s.(raw, "takeback_request")
      c = raw["clocks"]
      @you_ms = n.(c, "you_ms")
      @opponent_ms = n.(c, "opponent_ms")
      @running = s.(c, "running")
      @turn_left = n.(c, "your_turn_time_left_ms")
      @move_cap = n.(c, "move_cap_ms")
      @first_move_left = n.(c, "first_move_time_left_ms")
      r = raw["result"]
      @result_score = s.(r, "score")
      @result_winner = s.(r, "winner")
      @result_termination = s.(r, "termination")
      @tournament_id = s.(raw["tournament"], "id")
      @next_hint = s.(raw, "next")
      @ruleset = s.(raw, "ruleset").empty? ? "casual" : s.(raw, "ruleset") # "casual" or "fide_strict" (illegal moves are penalised, no takebacks, no hints)
      @rules_text = s.(raw, "rules_text")
      @hints_allowed = raw["hints_allowed"] != false # false in strict games: never draw legal-move dots
      @user_locale = s.(raw, "user_locale") # language (en fa ar es fr zh ru de pl ja ko) of the human behind your account: write notes and chat in it
      @opponent_locale = s.(raw, "opponent_locale")
      @illegal_attempts = []
    end

    def hints_allowed? = @hints_allowed
    def strict? = ruleset == "fide_strict"
    def rated? = @rated
    def your_turn? = @your_turn
    def over? = %w[finished aborted].include?(status)
    def legal_uci = legal_moves.map(&:uci)

    # Seconds you may think about this move (the smallest relevant clock), or nil when no clock applies.
    def seconds_for_this_move
      left = []
      if running == "you" && @turn_left.positive?
        left << @turn_left
      else
        left << @turn_left if @turn_left.positive?
        left << @first_move_left if @first_move_left.positive?
      end
      return nil if left.empty?

      left << @move_cap if @move_cap.positive?
      left.min / 1000.0
    end

    def with_attempts(attempts)
      dup.tap { |p| p.illegal_attempts = attempts }
    end

    def to_s = "Position(#{game_id} #{status} ply #{ply}#{your_turn? ? ' your turn' : ''})"
  end
end
