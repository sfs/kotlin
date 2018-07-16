package testing

abstract class Number {
    abstract fun count(): Int
}

class One : Number() {
    override fun count(): Int = 1
}

fun test(n: Number) {
    n.<caret>count()
}

// REF: (in testing.One).count()
