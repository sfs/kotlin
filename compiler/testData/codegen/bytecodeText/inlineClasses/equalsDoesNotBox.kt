// FILE: inlineClasses.kt
inline class A(val x: Int)
inline class B(val x: String)
inline class C(val x: Any?)

// FILE: test.kt
fun box(): String {
    val x = A(0)
    val y = A(1)
    if (x == y) return "Fail 1"
    if (x == null) return "Fail 11" // Vacuous

    val a = B("x")
    val b: B? = B("y")
    if (a == b) return "Fail 2"
    if (a == null) return "Fail 3"
    if (null == b) return "Fail 4"
    if (b.equals(null)) return "Fail 5"

    val s = C(null)
    if (null == s) return "Fail 7" // Vacuous

    return "OK"
}

// @TestKt.class:
// 0 INVOKESTATIC A.box-impl
// 0 INVOKEVIRTUAL A.unbox-impl
// 0 INVOKESTATIC B.box-impl
// 0 INVOKEVIRTUAL B.unbox-impl
// 0 INVOKESTATIC C.box-impl
// 0 INVOKEVIRTUAL C.unbox-impl
// 1 INVOKESTATIC A.equals-impl0
// 1 INVOKESTATIC B.equals-impl0