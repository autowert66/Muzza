import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main() {
    val inputBuffer = ByteBuffer.allocateDirect(10)
    inputBuffer.put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
    inputBuffer.flip()
    
    val data = inputBuffer.order(ByteOrder.nativeOrder())
    val shorts = data.asShortBuffer()
    val count = shorts.remaining()
    
    println("Count: $count")
    println("Input buffer position before: ${inputBuffer.position()}")
    for (i in 0 until count) {
        val scaled = shorts.get(i)
    }
    println("Input buffer position after: ${inputBuffer.position()}")
}
