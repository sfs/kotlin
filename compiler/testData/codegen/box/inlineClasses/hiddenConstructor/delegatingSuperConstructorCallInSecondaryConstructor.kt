// !LANGUAGE: +InlineClasses

inline class S(val string: String)

abstract class Base(val x: S)

class Test : Base {
    constructor() : super(S("OK"))
}

fun box() = Test().x.string