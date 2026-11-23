# frozen_string_literal: true

module Neuromate
  # Board helpers computed locally from a FEN: where the pieces are. Pure geometry, no evaluation.
  # Models read a FEN badly; the server sends the same facts (describe_position, square_info).
  module Board
    FILES = "abcdefgh"
    ORDER = "kqrbnp"
    KINDS = { "k" => "king", "q" => "queen", "r" => "rook", "b" => "bishop", "n" => "knight", "p" => "pawn" }.freeze

    # One piece: color "white"/"black", kind "king".."pawn", square "e4", symbol the FEN letter.
    Piece = Struct.new(:color, :kind, :square, :symbol)

    module_function

    # square => FEN letter (Crazyhouse pockets and "~" marks are ignored).
    def parse(fen)
      field = fen.strip.split(" ").first.to_s.split("[").first.to_s.delete("~")
      ranks = field.split("/")
      raise ArgumentError, "not a FEN: #{fen}" unless ranks.length == 8

      out = {}
      ranks.each_with_index do |row, i|
        f = 0
        row.each_char do |c|
          if c =~ /\d/
            f += c.to_i
          else
            out["#{FILES[f]}#{8 - i}"] = c
            f += 1
          end
        end
      end
      out
    end

    # Eight strings, rank 8 first, "." for empty (the server's board_rows).
    def rows(fen)
      b = parse(fen)
      8.downto(1).map { |r| (0...8).map { |f| b["#{FILES[f]}#{r}"] || "." }.join }
    end

    # ASCII diagram: ranks 8 to 1, files a-h, UPPER CASE = White, "." = empty; from_black turns it around.
    def diagram(fen, from_black: false)
      rs = rows(fen)
      lines = (0...8).map do |i|
        idx = from_black ? 7 - i : i
        row = from_black ? rs[idx].reverse : rs[idx]
        "#{8 - idx} #{row.chars.join(' ')}"
      end
      lines << "  #{from_black ? 'h g f e d c b a' : 'a b c d e f g h'}"
      lines.join("\n")
    end

    def piece_at(fen, square)
      sq = square.downcase
      c = parse(fen)[sq]
      c && piece(c, sq)
    end

    def piece(char, square)
      Piece.new(char == char.upcase ? "white" : "black", KINDS[char.downcase], square, char)
    end

    # Pieces of one color (nil = both): king, queen, rooks, bishops, knights, pawns, then by file and rank.
    def pieces(fen, color = nil)
      parse(fen).map { |sq, c| piece(c, sq) }.select { |p| color.nil? || p.color == color }
                .sort_by { |p| [p.color == "white" ? 0 : 1, ORDER.index(p.symbol.downcase), p.square[0], p.square[1]] }
    end

    # "King g1, Queen d1, Rooks a1 f1, ..." exactly as the server prints it.
    def pieces_text(fen, color)
      ps = pieces(fen, color)
      parts = ORDER.chars.filter_map do |k|
        sqs = ps.select { |p| p.symbol.downcase == k }.map(&:square)
        "#{KINDS[k].capitalize}#{sqs.length > 1 ? 's' : ''} #{sqs.join(' ')}" unless sqs.empty?
      end
      parts.empty? ? "none" : parts.join(", ")
    end
  end

  class Position
    # ASCII diagram, rank 8 on top, UPPER CASE = White. from_your_side turns it for Black.
    def board_diagram(from_your_side: false) = Board.diagram(fen, from_black: from_your_side && color == "black")

    # Eight strings, rank 8 first, "." for empty (the server's board_rows).
    def board_rows = Board.rows(fen)

    def piece_at(square) = Board.piece_at(fen, square)

    def pieces(color = nil) = Board.pieces(fen, color)

    # "King g1, Queen d1, Rooks a1 f1, ..."
    def pieces_text(color) = Board.pieces_text(fen, color)
  end

  class Client
    # The strategy guide. An empty topic returns the index and the six-step think-before-every-move checklist (read it before your first game).
    # Topics: checklist, opening, openings, middlegame, pawns, tactics, attack, endgame, blunders.
    def strategy_guide(topic = "") = call_tool("get_strategy_guide", clean(topic: topic)).text

    # Every piece of both sides with its square, side to move, castling, en passant and the last move in words.
    def describe_position(game_id = "") = call_tool("describe_position", clean(game_id: game_id)).text

    # What stands on a square and which pieces attack or defend it (geometry; occupancy only in fide_strict games).
    def square_info(square, game_id = "") = call_tool("square_info", clean(square: square, game_id: game_id)).text
  end
end
