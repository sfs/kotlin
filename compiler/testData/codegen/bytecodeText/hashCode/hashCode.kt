// IGNORE_BACKEND: JVM_IR
// On JVM_IR we generate the hash code implementation in place.

fun box(): String {
    true.hashCode()
    1.toByte().hashCode()
    1.toChar().hashCode()
    1.toShort().hashCode()
    1.hashCode()
    1L.hashCode()
    1.0F.hashCode()
    1.0.hashCode()
    "".hashCode()

    return "OK"
}

// 9 \.hashCode
// 9 \.hashCode \(\)I
