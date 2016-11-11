/*
 * Copyright (C) 2016 Language Technology Group and Interactive Graphics Systems Group, Technische Universität Darmstadt, Germany
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package controllers

import javax.inject.Inject

import model.faceted.search.{ FacetedSearch, Facets }
import model.{ Document, KeyTerm, Tag }
import play.api.cache.CacheApi
import play.api.libs.json.{ JsObject, JsValue, Json }
import play.api.mvc.{ Action, AnyContent, Controller, Request }
import util.SessionUtils.currentDataset
import util.TimeRangeParser

import scala.collection.mutable.ListBuffer

// scalastyle:off
import util.TupleWriters._
// scalastyle:on

// case class IteratorSession(hits: Long, hitIterator: Iterator[Long], hash: Long)
case class IteratorSession(hits: Long, hitIterator: Iterator[Document], hash: Long)

/*
    This class provides operations pertaining documents.
*/
class DocumentController @Inject() (cache: CacheApi) extends Controller {

  private val defaultPageSize = 50

  /**
   * returns the document with the id "id", if there is any
   */
  def getDocById(id: Int) = Action { implicit request =>
    val docSearch = Document.fromDBName(currentDataset)
    Ok(Json.toJson(docSearch.getById(id).map(doc => (doc.id, doc.created.toString(), doc.content)))).as("application/json")
  }

  def getDocsByLabel(label: String) = Action { implicit request =>
    val docIds = Tag.fromDBName(currentDataset).getByLabel(label).map { case Tag(_, docId, _) => docId }
    Ok(createJsonReponse(docIds, docIds.length))
  }

  // TODO: Extend ES API and remove KeyTerm API
  def getKeywordsById(id: Int, size: Int) = Action { implicit request =>
    val terms = KeyTerm.fromDBName(currentDataset).getDocumentKeyTerms(id, Some(size)).map {
      case KeyTerm(term, score) =>
        Json.obj("term" -> term, "score" -> score)
    }
    Ok(Json.toJson(terms)).as("application/json")
  }

  def getTagLabels() = Action { implicit request =>
    Ok(Json.obj("labels" -> Json.toJson(Tag.fromDBName(currentDataset).getDistinctLabels()))).as("application/json")
  }

  def addTag(id: Int, label: String) = Action { implicit request =>
    Ok(Json.obj("id" -> Tag.fromDBName(currentDataset).add(id, label).id)).as("application/json")
  }

  def removeTagById(tagId: Int) = Action { implicit request =>
    Tag.fromDBName(currentDataset).delete(tagId)
    Ok("success").as("Text")
  }

  def getTagsByDocId(id: Int) = Action { implicit request =>
    val tags = Tag.fromDBName(currentDataset).getByDocumentId(id).map {
      case Tag(tagId, _, label) =>
        Json.obj("id" -> tagId, "label" -> label)
    }
    Ok(Json.toJson(tags)).as("application/json")
  }

  /**
   * Search for Document by fulltext term and faceted search map via elasticsearch
   *
   * @param fullText full-text search term
   * @param generic   mapping of metadata key and a list of corresponding tags
   * @param entities list of entity ids to filter
   * @param timeRange string of a time range readable for [[TimeRangeParser]]
   * @return list of matching document id's
   */
  def getDocs(
    fullText: List[String],
    generic: Map[String, List[String]],
    entities: List[Long],
    timeRange: String,
    timeRangeX: String
  ) = Action { implicit request =>
    val uid = request.session.get("uid").getOrElse("0")
    val times = TimeRangeParser.parseTimeRange(timeRange)
    val timesX = TimeRangeParser.parseTimeRange(timeRangeX)
    val facets = Facets(fullText, generic, entities, times.from, times.to, timesX.from, timesX.to)
    var pageCounter = 0
    val facetSearch = FacetedSearch.fromIndexName(currentDataset)

    val cachedIterator = cache.get[IteratorSession](uid)
    // Initial page load or filter applied
    val iteratorSession = if (cachedIterator.isEmpty || cachedIterator.forall(_.hash != facets.hashCode())) {
      val (numDocs, it) = facetSearch.searchDocuments(facets, defaultPageSize)
      val session = IteratorSession(numDocs, it, facets.hashCode())
      cache.set(uid, session)
      session
      // Document list scrolled
    } else {
      cachedIterator.get
    }

    val docList = ListBuffer[Document]()
    while (iteratorSession.hitIterator.hasNext && pageCounter <= defaultPageSize) {
      docList += iteratorSession.hitIterator.next()
      pageCounter += 1
    }

    if (docList.size < defaultPageSize) {
      val newIteratorSession = IteratorSession(iteratorSession.hits, iteratorSession.hitIterator, -1)
      cache.set(uid, newIteratorSession)
    }

    Ok(createJsonReponse2(docList.toList, iteratorSession.hits))
  }

  def createJsonReponse2(docList: List[Document], hits: Long)(implicit request: Request[AnyContent]): JsValue = {
    if (docList.nonEmpty) {
      val docSearch = Document.fromDBName(currentDataset)

      val keys = docSearch.getMetadataKeysAndTypes().map(_._1)
      val docToMetadata = docSearch
        .getMetadataForDocuments(docList.map(_.id), keys)
        .groupBy(_._1)
        .map { case (id, l) => id -> l.collect { case (_, k, v) if !v.isEmpty => Json.obj("key" -> k, "val" -> v) } }

      val response = docList.map { d => Json.obj("id" -> d.id, "content" -> d.content, "highlighted" -> d.highlightedContent, "metadata" -> docToMetadata.get(d.id)) }

      Json.obj("hits" -> hits, "docs" -> response)
    } else {
      Json.obj("hits" -> 0, "docs" -> List[JsObject]())
    }
  }

  def createJsonReponse(docIds: List[Long], hits: Long)(implicit request: Request[AnyContent]): JsValue = {
    if (docIds.nonEmpty) {
      val docSearch = Document.fromDBName(currentDataset)

      val keys = docSearch.getMetadataKeysAndTypes().map(_._1)
      val docToMetadata = docSearch
        .getMetadataForDocuments(docIds, keys)
        .groupBy(_._1)
        .map { case (id, l) => id -> l.collect { case (_, k, v) if !v.isEmpty => Json.obj("key" -> k, "val" -> v) } }

      val response = docIds.map { id => Json.obj("id" -> id, "metadata" -> docToMetadata.get(id)) }

      Json.obj("hits" -> hits, "docs" -> response)
    } else {
      Json.obj("hits" -> 0, "docs" -> List[JsObject]())
    }
  }
}
