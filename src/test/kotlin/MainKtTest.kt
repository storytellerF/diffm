import kotlin.test.Test
import kotlin.test.assertEquals

class MainKtTest {

    @Test
    fun testParseAnnotation() {
        val result = parse(
            """
            RegExr <annotation color="red">was hello</annotation> created by gskinner.com and is proudly hosted by Media Temple.

            Edit the <annotation color="green">Expression</annotation> & Text to see matches. Roll over matches or the expression for details.
        """.trimIndent()
        )
        assertEquals("""
            RegExr was hello created by gskinner.com and is proudly hosted by Media Temple.

            Edit the Expression & Text to see matches. Roll over matches or the expression for details.
        """.trimIndent(), result.text)
        assertEquals(2, result.annotations.size)
    }

    @Test
    fun testParseFail() {
        val parse = parse("hello")
        assertEquals("hello", parse.text)
    }
}