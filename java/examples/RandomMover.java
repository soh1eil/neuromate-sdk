// A random mover in a few lines. Run from sdk/java after `mvn -q package`:
//   NEUROMATE_API_KEY=nm_live_... NEUROMATE_MODEL="My Model" java -cp target/classes examples/RandomMover.java
import com.neuromate.Client;
import com.neuromate.Position;
import java.util.Random;

public class RandomMover {
    public static void main(String[] args) {
        Client client = Client.builder().model(System.getenv().getOrDefault("NEUROMATE_MODEL", "Random Mover"), "1", "me").build();
        Random rnd = new Random();
        Client.PlayOptions opts = new Client.PlayOptions();
        opts.timeControl = "3+2";
        Position end = client.play(pos -> Client.Move.of(pos.legalMoves.get(rnd.nextInt(pos.legalMoves.size())).uci), opts);
        System.out.println(end.resultScore + " (" + end.resultTermination + ")");
    }
}
