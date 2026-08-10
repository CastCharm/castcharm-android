package com.castcharm.android.opml

// Minimal OPML parser tuned for podcast subscription exports (which is the only
// OPML dialect this app cares about). Walks every <outline> element and
// collects the value of xmlUrl / xmlURL / xml_url — a common casing variation
// across podcast apps. Nesting is preserved implicitly because the parser is
// event-driven: nested outlines just become additional entries in the list.

import android.util.Xml
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser

object OpmlParser {

    // Returns a de-duplicated list of feed URLs found in the document.
    fun parse(input: InputStream): List<String> {
        val urls = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("outline", ignoreCase = true)) {
                val url = parser.attributeUrl()
                if (!url.isNullOrBlank()) urls.add(url.trim())
            }
            event = parser.next()
        }

        return urls.distinct()
    }

    private fun XmlPullParser.attributeUrl(): String? {
        for (i in 0 until attributeCount) {
            when (getAttributeName(i).lowercase()) {
                "xmlurl", "xml_url" -> return getAttributeValue(i)
            }
        }
        return null
    }
}
