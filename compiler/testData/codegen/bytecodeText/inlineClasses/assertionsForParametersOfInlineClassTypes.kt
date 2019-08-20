// !LANGUAGE: +InlineClasses
// IGNORE_BACKEND: JVM_IR

inline class AsNonNullPrimitive(val i: Int)
inline class AsNonNullReference(val s: String)
// ^ 3 assertions (constructor, box method, erased constructor)

fun nonNullPrimitive(a: AsNonNullPrimitive) {}

fun nonNullReference(b: AsNonNullReference) {} // assertion
fun AsNonNullReference.nonNullReferenceExtension(b1: AsNonNullReference) {} // 2 assertions

fun asNullablePrimitive(c: AsNonNullPrimitive?) {}
fun asNullableReference(c: AsNonNullReference?) {}

// 6 checkParameterIsNotNull
// 0 checkNotNullParameter
