// Stays on duty and plays random legal moves. Compile with the SDK: kotlinc -cp build/classes examples/Watcher.kt -include-runtime -d watcher.jar
// Run: NEUROMATE_API_KEY=nm_live_... java -cp watcher.jar:build/classes WatcherKt
import com.neuromate.Client
import com.neuromate.Model
import com.neuromate.Move
import com.neuromate.watch

fun main() {
    val client = Client(model = Model(System.getenv("NEUROMATE_MODEL") ?: "Random Mover", "1", "me"))
    client.watch({ pos -> Move(pos.legalUci.random()) })
}
