/*
 * Module: r2-streamer-kotlin
 * Developers: Quentin Gliosca
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.streamer.parser.epub

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Properties
import org.readium.r2.shared.publication.PublicationCollection
import org.readium.r2.shared.publication.encryption.Encryption
import org.readium.r2.streamer.parser.normalize


internal data class Epub(
        val packageDocument: PackageDocument,
        val navigationData: Map<String, List<Link>> = emptyMap(),
        val encryptionData: Map<String, Encryption> = emptyMap()
) {
    fun toPublication() : Publication {
        val metadata = with(packageDocument) {
            metadata.globalItems.toMetadata(metadata.uniqueIdentifierId, spine.direction)
        }

        // Compute links
        @Suppress("Unchecked_cast")
        val itemById = packageDocument.manifest.filter { it.id != null }.associateBy(Item::id) as Map<String, Item>
        val itemrefByIdref = packageDocument.spine.itemrefs.associateBy(Itemref::idref)
        val links = packageDocument.manifest.map { computeLink(it, itemById, itemrefByIdref) }
        val readingOrderIds = computeReadingOrderIds(links, itemrefByIdref)
        val (readingOrder, resources) = links.partition { it.title in readingOrderIds }

        // Compute toc and otherCollections
        val toc = navigationData["toc"].orEmpty()
        val otherCollections = navigationData.minus("toc").map { PublicationCollection(links=it.value, role=it.key) }

        // Build Publication object
        return Publication(
                metadata = metadata,
                links = packageDocument.metadata.links.mapNotNull(::mapLink),
                readingOrder = readingOrder,
                resources = resources,
                tableOfContents = toc,
                otherCollections = otherCollections
        ).apply {
            type = Publication.TYPE.EPUB
            version = packageDocument.epubVersion
        }
    }

    private fun mapLink(link: EpubLink) : Link? {
        val contains: MutableList<String> = mutableListOf()
        if (link.rel.contains(Vocabularies.Link + "record")) {
            if (link.properties.contains(Vocabularies.Link + "onix"))
                contains.add("onix")
            if (link.properties.contains(Vocabularies.Link + "xmp"))
                contains.add("xmp")
        }
        return Link(
                href = link.href,
                type = link.mediaType,
                rels = link.rel,
                properties = Properties(mapOf("contains" to contains))
        )
    }

    private fun computeReadingOrderIds(links: List<Link>, itemrefByIdref: Map<String, Itemref>) : Set<String> {
        val ids: MutableSet<String> = mutableSetOf()
        for (l in links) {
            if (itemrefByIdref.containsKey(l.title) && (itemrefByIdref[l.title] as Itemref).linear) {
                ids.addAll(computeIdChain(l))
            }
        }
        return ids
    }

    private fun computeIdChain(link: Link) : Set<String> {
        // The termination has already been checked while computing links
        val ids: MutableSet<String> = mutableSetOf( link.title as String )
        for (a in link.alternates) {
            ids.addAll(computeIdChain(a))
        }
        return ids
    }

    private fun computeLink(
            item: Item,
            itemById: Map<String, Item>,
            itemrefByIdref: Map<String, Itemref>,
            fallbackChain: Set<String> = emptySet()) : Link {

        val (rels, properties) = computePropertiesAndRels(item, itemrefByIdref[item.id])
        val alternates = computeAlternates(item, itemById, itemrefByIdref, fallbackChain)
        val duration = packageDocument.metadata.refineItems[item.id]?.duration()

        return Link(
                title = item.id,
                href = normalize(packageDocument.path, item.href),
                type = item.mediaType,
                duration = duration,
                rels = rels,
                properties = properties,
                alternates = alternates
        )
    }

    private fun computePropertiesAndRels(item: Item, itemref: Itemref?) : Pair<List<String>, Properties> {
        val properties: MutableMap<String, Any> = mutableMapOf()
        val rels: MutableList<String> = mutableListOf()
        val (manifestRels, contains, others) = parseItemProperties(item.properties)
        rels.addAll(manifestRels)
        if (contains.isNotEmpty()) {
            properties["contains"] = contains
        }
        if (others.isNotEmpty()) {
            properties["others"] = others
        }
        if (itemref != null) {
            properties.putAll(parseItemrefProperties(itemref.properties))
        }

        if (packageDocument.epubVersion < 3.0) {
            val coverId = packageDocument.metadata.globalItems.cover()
            if (coverId != null && item.id == coverId) rels.add("cover")
        }

        encryptionData[item.href]?.let {
            properties["encrypted"] = it
        }

        return Pair(rels, Properties(properties))
    }

    private fun computeAlternates(
            item: Item,
            itemById: Map<String, Item>,
            itemrefByIdref: Map<String, Itemref>,
            fallbackChain: Set<String>) : List<Link> {

        val fallback = item.fallback?.let { id ->
            if (id in fallbackChain) null else
                itemById[id]?.let {
                    val updatedChain = if (item.id != null) fallbackChain + item.id else fallbackChain
                    computeLink(it, itemById, itemrefByIdref, updatedChain) }
        }
        val mediaOverlays = item.mediaOverlay?.let { id ->
            itemById[id]?.let {
                computeLink(it, itemById, itemrefByIdref) }
        }
        return listOfNotNull(fallback, mediaOverlays)
    }

    private fun parseItemProperties(properties: List<String>) : Triple<List<String>, List<String>, List<String>> {
        val rels: MutableList<String> = mutableListOf()
        val contains: MutableList<String> = mutableListOf()
        val others: MutableList<String> = mutableListOf()
        for (property in properties) {
            when (property) {
                Vocabularies.Item + "scripted" -> contains.add("js")
                Vocabularies.Item + "mathml" -> contains.add("mathml")
                Vocabularies.Item + "svg" -> contains.add("svg")
                Vocabularies.Item + "xmp-record" -> contains.add("xmp")
                Vocabularies.Item + "remote-resources" -> contains.add("remote-resources")
                Vocabularies.Item + "nav" -> rels.add("contents")
                Vocabularies.Item + "cover-image" -> rels.add("cover")
                else -> others.add(property)
            }
        }
        return Triple(rels, contains, others)
    }

    private fun parseItemrefProperties(properties: List<String>) : Map<String, String> {
        val linkProperties: MutableMap<String, String> = mutableMapOf()
        for (property in properties) {
            //  Page
            when (property) {
                Vocabularies.Rendition + "page-spread-center" -> "center"
                Vocabularies.Rendition + "page-spread-left",
                Vocabularies.Itemref + "page-spread-left" -> "left"
                Vocabularies.Rendition + "page-spread-right",
                Vocabularies.Itemref + "page-spread-right" -> "right"
                else -> null
            }?.let { linkProperties["page"] = it }
            //  Spread
            when (property) {
                Vocabularies.Rendition + "spread-node" -> "none"
                Vocabularies.Rendition + "spread-auto" -> "auto"
                Vocabularies.Rendition + "spread-landscape" -> "landscape"
                Vocabularies.Rendition + "spread-portrait",
                Vocabularies.Rendition + "spread-both" -> "both"
                else -> null
            }?.let { linkProperties["spread"] = it }
            //  Layout
            when (property) {
                Vocabularies.Rendition + "layout-reflowable" -> "reflowable"
                Vocabularies.Rendition + "layout-pre-paginated" -> "fixed"
                else -> null
            }?.let { linkProperties["layout"] = it }
            //  Orientation
            when (property) {
                Vocabularies.Rendition + "orientation-auto" -> "auto"
                Vocabularies.Rendition + "orientation-landscape" -> "landscape"
                Vocabularies.Rendition + "orientation-portrait" -> "portrait"
                else -> null
            }?.let { linkProperties["orientation"] = it }
            //  Overflow
            when (property) {
                Vocabularies.Rendition + "flow-auto" -> "auto"
                Vocabularies.Rendition + "flow-paginated" -> "paginated"
                Vocabularies.Rendition + "flow-scrolled-continuous",
                Vocabularies.Rendition + "flow-scrolled-doc" -> "scrolled"
                else -> null
            }?.let { linkProperties["overflow"] = it }
        }
        return linkProperties
    }
}
