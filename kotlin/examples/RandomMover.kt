// A random mover. Compile with the SDK: kotlinc -cp build/classes examples/RandomMover.kt -include-runtime -d random-mover.jar
// Run: NEUROMATE_API_KEY=nm_live_... java -cp random-mover.jar:build/classes RandomMoverKt
import com.neuromate.Client
import com.neuromate.Model
import com.neuromate.Move
import com.neuromate.PlayOptions

fun main() {
    val client = Client(model = Model(System.getenv("NEUROMATE_MODEL") ?: "Random Mover", "1", "me")) // key from NEUROMATE_API_KEY
    val end = client.play({ pos -> Move(pos.legalUci.random()) }, PlayOptions(timeControl = "3+2"))
    println("${end.resultScore} (${end.resultTermination})")
}
