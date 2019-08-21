// FILE: inlineClasses.kt
inline class A(val x: Int)
inline class B(val x: String)
inline class C(val x: Any?)

// FILE: test.kt

// False
fun isNullVacuousLeftA(s: A) = s == null
fun isNullVacuousRightA(s: A) = null == s
// IFNONNULL
fun isNullLeftA(s: A?) = s == null
fun isNullRightA(s: A?) = null == s
// equals-impl0
fun isEqualSameA(s: A, t: A) = s == t
// equals-impl
fun isEqualAnyLeftA(s: A, t: Any?) = s == t
fun isEqualAnyRightA(s: Any?, t: A) = s == t
// Intrinsics.areEqual
fun isEqualSameNullableA(s: A?, t: A?) = s == t
fun isEqualAnyNullableLeftA(s: A?, t: Any?) = s == t
fun isEqualAnyNullableRightA(s: Any?, t: A?) = s == t

// False
fun isNullVacuousLeftB(s: B) = s == null
fun isNullVacuousRightB(s: B) = null == s
// IFNONNULL
fun isNullLeftB(s: B?) = s == null
fun isNullRightB(s: B?) = null == s
// equals-impl0
fun isEqualSameB(s: B, t: B) = s == t
// equals-impl
fun isEqualAnyLeftB(s: B, t: Any?) = s == t
fun isEqualAnyRightB(s: Any?, t: B) = s == t
// equals-impl0
fun isEqualSameNullableB(s: B?, t: B?) = s == t
// equals-impl
fun isEqualAnyNullableLeftB(s: B?, t: Any?) = s == t
fun isEqualAnyNullableRightB(s: Any?, t: B?) = s == t

// False
fun isNullVacuousLeftC(s: C) = s == null
fun isNullVacuousRightC(s: C) = null == s
// IFNONULL
fun isNullLeftC(s: C?) = s == null
fun isNullRightC(s: C?) = null == s
// equals-impl0
fun isEqualSameC(s: C, t: C) = s == t
// equals-impl
fun isEqualAnyLeftC(s: C, t: Any?) = s == t
fun isEqualAnyRightC(s: Any?, t: C) = s == t
// Intrinsics.areEqual
fun isEqualSameNullableC(s: C?, t: C?) = s == t
fun isEqualAnyNullableLeftC(s: C?, t: Any?) = s == t
fun isEqualAnyNullableRightC(s: Any?, t: C?) = s == t

// @TestKt.class:
// 0 INVOKESTATIC A.box-impl
// 0 INVOKEVIRTUAL A.unbox-impl
// 0 INVOKESTATIC B.box-impl
// 0 INVOKEVIRTUAL B.unbox-impl
// 0 INVOKESTATIC C.box-impl
// 0 INVOKEVIRTUAL C.unbox-impl
// 2 INVOKESTATIC A.equals-impl \(
// 1 INVOKESTATIC A.equals-impl0
// 4 INVOKESTATIC B.equals-impl \(
// 2 INVOKESTATIC B.equals-impl0
// 2 INVOKESTATIC C.equals-impl \(
// 1 INVOKESTATIC C.equals-impl0
// 6 INVOKESTATIC kotlin/jvm/internal/Intrinsics.areEqual