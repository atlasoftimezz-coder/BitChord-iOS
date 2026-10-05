package com.music.bitchord.compat

/*
 * A small, non-validating XML reader exposing the slice of the W3C DOM that the
 * ported TTML parser uses (getElementsByTagName, getAttribute, childNodes,
 * textContent...). javax.xml is JVM-only; TTML lyric documents are small and
 * well-formed, so this is all that is needed. It never resolves DTDs or
 * external entities — there is nothing here that could fetch anything.
 */

abstract class Node internal constructor(val nodeType: Short) {
    internal val children = mutableListOf<Node>()
    var parentNode: Element? = null
        internal set

    val childNodes: NodeList get() = NodeList(children)
    abstract val textContent: String?
    open val nodeName: String get() = ""
    open val nodeValue: String? get() = null
    open val localName: String? get() = null

    companion object {
        const val ELEMENT_NODE: Short = 1
        const val ATTRIBUTE_NODE: Short = 2
        const val TEXT_NODE: Short = 3
        const val DOCUMENT_NODE: Short = 9
    }
}

class NodeList internal constructor(private val nodes: List<Node>) {
    val length: Int get() = nodes.size
    fun item(index: Int): Node? = nodes.getOrNull(index)
}

class Attr internal constructor(private val name: String, private val value: String) : Node(ATTRIBUTE_NODE) {
    override val nodeName: String get() = name
    override val nodeValue: String get() = value
    override val localName: String get() = name.substringAfter(':')
    override val textContent: String get() = value
}

class NamedNodeMap internal constructor(private val attrs: List<Attr>) {
    val length: Int get() = attrs.size
    fun item(index: Int): Attr? = attrs.getOrNull(index)
}

open class Element internal constructor(val tagName: String) : Node(ELEMENT_NODE) {
    internal val attributeList = mutableListOf<Attr>()

    override val nodeName: String get() = tagName
    override val localName: String get() = tagName.substringAfter(':')
    val attributes: NamedNodeMap get() = NamedNodeMap(attributeList)

    /** "" when absent, like the DOM. */
    fun getAttribute(name: String): String =
        attributeList.firstOrNull { it.nodeName == name }?.nodeValue.orEmpty()

    fun getElementsByTagName(name: String): NodeList {
        val found = mutableListOf<Node>()
        fun walk(node: Node) {
            node.children.forEach { child ->
                if (child is Element) {
                    if (name == "*" || child.tagName == name) found += child
                    walk(child)
                }
            }
        }
        walk(this)
        return NodeList(found)
    }

    override val textContent: String
        get() = buildString { appendText(this@Element, this) }

    private fun appendText(node: Node, out: StringBuilder) {
        node.children.forEach {
            when (it) {
                is Text -> out.append(it.data)
                is Element -> appendText(it, out)
            }
        }
    }
}

class Text internal constructor(val data: String) : Node(TEXT_NODE) {
    override val nodeName: String get() = "#text"
    override val nodeValue: String get() = data
    override val textContent: String get() = data
}

class Document internal constructor() : Element("#document") {
    val documentElement: Element? get() = children.firstOrNull { it is Element } as? Element
}

object MiniXml {
    /** @throws IllegalArgumentException on malformed input. */
    fun parse(xml: String): Document {
        val document = Document()
        var current: Element = document
        var i = 0
        val n = xml.length
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                current.children += Text(decodeEntities(text.toString())).also { it.parentNode = current }
                text.setLength(0)
            }
        }

        while (i < n) {
            val c = xml[i]
            if (c != '<') {
                text.append(c)
                i++
                continue
            }
            when {
                xml.startsWith("<!--", i) -> {
                    val end = xml.indexOf("-->", i + 4)
                    require(end >= 0) { "Unterminated comment" }
                    i = end + 3
                }
                xml.startsWith("<![CDATA[", i) -> {
                    val end = xml.indexOf("]]>", i + 9)
                    require(end >= 0) { "Unterminated CDATA" }
                    text.append(xml, i + 9, end)
                    i = end + 3
                }
                xml.startsWith("<?", i) -> {
                    val end = xml.indexOf("?>", i + 2)
                    require(end >= 0) { "Unterminated processing instruction" }
                    i = end + 2
                }
                xml.startsWith("<!", i) -> {
                    // DOCTYPE and friends: skipped, never resolved.
                    var depth = 0
                    var j = i + 2
                    while (j < n) {
                        val ch = xml[j]
                        if (ch == '[') depth++
                        if (ch == ']') depth--
                        if (ch == '>' && depth <= 0) break
                        j++
                    }
                    i = j + 1
                }
                xml.startsWith("</", i) -> {
                    flushText()
                    val end = xml.indexOf('>', i + 2)
                    require(end >= 0) { "Unterminated end tag" }
                    val name = xml.substring(i + 2, end).trim()
                    require(current !== document && current.tagName == name) { "Mismatched </$name>" }
                    current = current.parentNode ?: document
                    i = end + 1
                }
                else -> {
                    flushText()
                    var j = i + 1
                    while (j < n && !xml[j].isWhitespace() && xml[j] != '>' && xml[j] != '/') j++
                    val element = Element(xml.substring(i + 1, j))
                    while (true) {
                        while (j < n && xml[j].isWhitespace()) j++
                        require(j < n) { "Unterminated start tag" }
                        if (xml[j] == '>' || xml[j] == '/') break
                        val nameStart = j
                        while (j < n && xml[j] != '=' && !xml[j].isWhitespace() && xml[j] != '>' && xml[j] != '/') j++
                        val attrName = xml.substring(nameStart, j)
                        while (j < n && xml[j].isWhitespace()) j++
                        if (j < n && xml[j] == '=') {
                            j++
                            while (j < n && xml[j].isWhitespace()) j++
                            require(j < n) { "Unterminated attribute $attrName" }
                            val quote = xml[j]
                            require(quote == '"' || quote == '\'') { "Unquoted attribute $attrName" }
                            val valueEnd = xml.indexOf(quote, j + 1)
                            require(valueEnd >= 0) { "Unterminated attribute $attrName" }
                            element.attributeList += Attr(attrName, decodeEntities(xml.substring(j + 1, valueEnd)))
                            j = valueEnd + 1
                        } else {
                            element.attributeList += Attr(attrName, "")
                        }
                    }
                    val selfClosing = xml[j] == '/'
                    val close = xml.indexOf('>', j)
                    require(close >= 0) { "Unterminated start tag" }
                    element.parentNode = current
                    current.children += element
                    if (!selfClosing) current = element
                    i = close + 1
                }
            }
        }
        flushText()
        return document
    }

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

    fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        return ENTITY.replace(value) { match ->
            when (val name = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (name.startsWith("#x")) name.drop(2).toInt(16) else name.drop(1).toInt()
                    codePointToString(code)
                }
            }
        }
    }

    private fun codePointToString(code: Int): String =
        if (code < 0x10000) {
            code.toChar().toString()
        } else {
            val v = code - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
}
