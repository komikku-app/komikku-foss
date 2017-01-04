package exh.search

import exh.metadata.models.ExGalleryMetadata
import exh.metadata.models.Tag

class SearchEngine {

    private val queryCache = mutableMapOf<String, List<QueryComponent>>()

    fun matches(metadata: ExGalleryMetadata, query: List<QueryComponent>): Boolean {

        fun matchTagList(tags: List<Tag>,
                         component: Text): Boolean {
            //Match tags
            val tagMatcher = if(!component.exact)
                component.asLenientRegex()
            else
                component.asRegex()
            //Match beginning of tag
            if (tags.find {
                tagMatcher.testExact(it.name)
            } != null) {
                if(component.excluded) return false
            } else {
                //No tag matched for this component
                return false
            }
            return true
        }

        for(component in query) {
            if(component is Text) {
                //Match title
                if (component.asRegex().test(metadata.title?.toLowerCase())
                        || component.asRegex().test(metadata.altTitle?.toLowerCase())) {
                    continue
                }
                //Match tags
                if(!matchTagList(metadata.tags.entries.flatMap { it.value },
                        component)) return false
            } else if(component is Namespace) {
                if(component.namespace == "uploader") {
                    //Match uploader
                    if(!component.tag?.rawTextOnly().equals(metadata.uploader,
                            ignoreCase = true)) {
                        return false
                    }
                } else {
                    //Match namespace
                    val ns = metadata.tags.entries.filter {
                        it.key == component.namespace
                    }.flatMap { it.value }
                    //Match tags
                    if (!matchTagList(ns, component.tag!!))
                        return false
                }
            }
        }
        return true
    }

    fun parseQuery(query: String) = queryCache.getOrPut(query, {
        val res = mutableListOf<QueryComponent>()

        var inQuotes = false
        val queuedRawText = StringBuilder()
        val queuedText = mutableListOf<TextComponent>()
        var namespace: Namespace? = null

        var nextIsExcluded = false
        var nextIsExact = false

        fun flushText() {
            if(queuedRawText.isNotEmpty()) {
                queuedText += StringTextComponent(queuedRawText.toString())
                queuedRawText.setLength(0)
            }
        }

        fun flushToText() = Text().apply {
            components += queuedText
            queuedText.clear()
        }

        fun flushAll() {
            flushText()
            if (queuedText.isNotEmpty()) {
                val component = namespace?.apply {
                    tag = flushToText()
                } ?: flushToText()
                component.excluded = nextIsExcluded
                component.exact = nextIsExact
                res += component
            }
        }

        for(char in query.toLowerCase()) {
            if(char == '"') {
                inQuotes = !inQuotes
            } else if(char == '?' || char == '_') {
                flushText()
                queuedText.add(SingleWildcard())
            } else if(char == '*' || char == '%') {
                flushText()
                queuedText.add(MultiWildcard())
            } else if(char == '-') {
                nextIsExcluded = true
            } else if(char == '$') {
                nextIsExact = true
            } else if(char == ':') {
                flushText()
                var flushed = flushToText().rawTextOnly()
                //Map tag aliases
                flushed = when(flushed) {
                    "a" -> "artist"
                    "c", "char" -> "character"
                    "f" -> "female"
                    "g", "creator", "circle" -> "group"
                    "l", "lang" -> "language"
                    "m" -> "male"
                    "p", "series" -> "parody"
                    "r" -> "reclass"
                    else -> flushed
                }
                namespace = Namespace(flushed, null)
            } else if(char == ' ' && !inQuotes) {
                flushAll()
            } else {
                queuedRawText.append(char)
            }
        }
        flushAll()

        res
    })
}
