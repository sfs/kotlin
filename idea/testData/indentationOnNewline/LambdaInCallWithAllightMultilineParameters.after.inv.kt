fun some(f: () -> Unit, s: String) {}
fun test() {
    some({
        val test = 1
        <caret>
        val more = 1
    }, "hello")
}

// SET_TRUE: ALIGN_MULTILINE_PARAMETERS_IN_CALLS
