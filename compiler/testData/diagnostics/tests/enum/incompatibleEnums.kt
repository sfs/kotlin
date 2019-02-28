// !LANGUAGE: -ProhibitComparisonOfIncompatibleEnums

enum class E1 {
    A, B
}

enum class E2 {
    A, B
}

fun foo1(e1: E1, e2: E2) {
    <!INCOMPATIBLE_ENUM_COMPARISON!>e1 == e2<!>
    <!INCOMPATIBLE_ENUM_COMPARISON!>e1 != e2<!>

    <!INCOMPATIBLE_ENUM_COMPARISON!>e1 == E2.A<!>
    <!INCOMPATIBLE_ENUM_COMPARISON!>E1.B == e2<!>

    <!INCOMPATIBLE_ENUM_COMPARISON!>E1.A == E2.B<!>

    e1 == E1.A
    E1.A == e1
    e2 == E2.B
    E2.B == e2
}

fun foo2(e1: E1, e2: E2) {
    when (e1) {
        E1.A -> {}
        <!INCOMPATIBLE_ENUM_COMPARISON!>E2.A<!> -> {}
        <!INCOMPATIBLE_ENUM_COMPARISON!>E2.B<!> -> {}
        e1 -> {}
        <!INCOMPATIBLE_ENUM_COMPARISON!>e2<!> -> {}
        else -> {}
    }
}

fun foo3(e1: Enum<E1>, e2: Enum<E2>, e: Enum<*>) {
    e1 == e
    e1 == e2

    e1 == E1.A
    <!INCOMPATIBLE_ENUM_COMPARISON!>e1 == E2.A<!>

    when (e1) {
        e1 -> {}
        e2 -> {}
        e -> {}
        E1.A -> {}
        <!INCOMPATIBLE_ENUM_COMPARISON!>E2.A<!> -> {}
        else -> {}
    }

    when (e) {
        e -> {}
        e2 -> {}
        E1.A -> {}
        E2.A -> {}
        else -> {}
    }
}

interface MyInterface
open class MyOpenClass

fun foo4(e1: E1, i: MyInterface, c: MyOpenClass) {
    <!INCOMPATIBLE_ENUM_COMPARISON!>e1 == i<!>
    <!INCOMPATIBLE_ENUM_COMPARISON!>i == e1<!>

    <!INCOMPATIBLE_ENUM_COMPARISON!>e1 == c<!>
    <!INCOMPATIBLE_ENUM_COMPARISON!>c == e1<!>

    when (e1) {
        <!INCOMPATIBLE_ENUM_COMPARISON!>i<!> -> {}
        <!INCOMPATIBLE_ENUM_COMPARISON!>c<!> -> {}
        else -> {}
    }
}

enum class E3 : MyInterface { X, Y }

fun foo5(i: MyInterface, a: Any) {
    E3.X == E3.Y
    E3.X == i
    E3.X == a
}

fun foo6(e1: E1?, e2: E2) {
    <!SENSELESS_COMPARISON!>E1.A == null<!>
    <!SENSELESS_COMPARISON!>null == E1.A<!>
    e1 == null
    null == e1

    <!INCOMPATIBLE_ENUM_COMPARISON!>e1 == E2.A<!>
    <!INCOMPATIBLE_ENUM_COMPARISON!>E2.A == e1<!>
    <!INCOMPATIBLE_ENUM_COMPARISON!>e1 == e2<!>
    <!INCOMPATIBLE_ENUM_COMPARISON!>e2 == e1<!>

    <!SENSELESS_COMPARISON!>e2 == null<!>
    <!SENSELESS_COMPARISON!>null == e2<!>
    <!SENSELESS_COMPARISON!>E1.A == null<!>
    <!SENSELESS_COMPARISON!>null == E1.A<!>
}

fun foo7(e1: E1?, e2: E2?) {
    e1 == e2 // There should be an IDE-inspection for such cases
}