class A {
    operator fun invoke(i: Int) {}
}

fun usage(a: A) {
    a(42)
    A()(42)
    a.invoke(42)
}