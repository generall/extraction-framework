package org.dbpedia.extraction.mappings

import java.util.logging.Logger
import org.dbpedia.extraction.config.provenance.DBpediaDatasets
import org.dbpedia.extraction.transform.Quad
import org.dbpedia.extraction.wikiparser.{NodeUtil, TemplateNode}
import org.dbpedia.extraction.ontology.{Ontology, OntologyClass, OntologyProperty}
import org.dbpedia.extraction.util.Language
import scala.collection.mutable.{Buffer,ArrayBuffer}
import org.dbpedia.extraction.config.dataparser.DataParserConfig
import scala.language.reflectiveCalls

class IntermediateNodeMapping (
  val nodeClass : OntologyClass, // public for rml mappings
  val correspondingProperty : OntologyProperty, //public for rml mappings
  val mappings : List[PropertyMapping], // must be public val for statistics
  context : {
    def ontology : Ontology
    def language : Language 
  }
)
extends PropertyMapping
{
  private val logger = Logger.getLogger(classOf[IntermediateNodeMapping].getName)

  private val splitRegex = if (DataParserConfig.splitPropertyNodeRegexInfobox.contains(context.language.wikiCode))
                             DataParserConfig.splitPropertyNodeRegexInfobox.get(context.language.wikiCode).get
                           else DataParserConfig.splitPropertyNodeRegexInfobox.get("en").get

  override val datasets = mappings.flatMap(_.datasets).toSet ++ Set(DBpediaDatasets.OntologyTypes, DBpediaDatasets.OntologyTypesTransitive, DBpediaDatasets.OntologyPropertiesObjects)
    

  override def extract(node : TemplateNode, subjectUri : String) : Seq[Quad] =
  {
    var graph = new ArrayBuffer[Quad]()

    val affectedTemplatePropertyNodes = mappings.flatMap(_ match {
      case spm : SimplePropertyMapping => node.property(spm.templateProperty)
      case _ => None
    }).toSet //e.g. Set(leader_name, leader_title)

    val valueNodes = affectedTemplatePropertyNodes.map(NodeUtil.splitPropertyNode(_, splitRegex))

    //more than one template proerty is affected (e.g. leader_name, leader_title)
    if(affectedTemplatePropertyNodes.size > 1)
    {
      //require their values to be all singles
      if(valueNodes.forall(_.size <= 1))
      {
        createInstance(graph, node, subjectUri)
      }
      else
      {
        // TODO muliple properties having multiple values
        // happens about 7000 times in the extraction of about 15 languages.
        /**
         * fictive example:
         * leader_name = Bill_Gates<br>Steve_Jobs
         * leader_title = Microsoft dictator<br>Apple evangelist
         */
        // TODO: better logging. include page name, template name and maybe even values in log message.
        // But first, improve the logging configuration. Most log output should not go to stdout/stderr. 
        // logger.warning("IntermediateNodeMapping for muliple properties having multiple values not implemented!")
      }
    }
    //one template property is affected (e.g. engine)
    else if(affectedTemplatePropertyNodes.size == 1)
    {
      //allow multiple values in this property
      for(valueNodesForOneProperty <- valueNodes; value <- valueNodesForOneProperty)
      {
        createInstance(graph, value.parent.asInstanceOf[TemplateNode], subjectUri)
      }
    }

    graph
  }

  private def createInstance(graph: Buffer[Quad], node : TemplateNode, originalSubjectUri : String): Unit =
  {
    val instanceUri = node.generateUri(originalSubjectUri, node)
    
    // extract quads
    val values = mappings.flatMap(_.extract(node, instanceUri))

    // only generate triples if we actually extracted some values
    if(! values.isEmpty)
    {
      graph += new Quad(context.language, DBpediaDatasets.OntologyPropertiesObjects, originalSubjectUri, correspondingProperty, instanceUri, node.sourceIri);
      
      for (cls <- nodeClass.relatedClasses) {
        // Here we split the transitive types from the direct type assignment
        val typeDataset = if (cls.equals(nodeClass)) DBpediaDatasets.OntologyTypes else DBpediaDatasets.OntologyTypesTransitive
        graph += new Quad(context.language, typeDataset, instanceUri, context.ontology.properties("rdf:type"), cls.uri, node.sourceIri)
      }
      
      graph ++= values
    }
  }
}
