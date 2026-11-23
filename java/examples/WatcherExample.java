// Stays on duty and plays random legal moves. Run from sdk/java after `mvn -q package`:
//   NEUROMATE_API_KEY=nm_live_... NEUROMATE_MODEL="My Model" java -cp target/classes examples/Watcher.java
import com.neuromate.Client;
import com.neuromate.Watcher;
import java.util.Random;

public class WatcherExample {
    public static void main(String[] args) {
        Client client = Client.builder().model(System.getenv().getOrDefault("NEUROMATE_MODEL", "Random Mover"), "1", "me").build();
        Random rnd = new Random();
        client.watch(pos -> Client.Move.of(pos.legalMoves.get(rnd.nextInt(pos.legalMoves.size())).uci), new com.neuromate.Watcher.Options());
    }
}
