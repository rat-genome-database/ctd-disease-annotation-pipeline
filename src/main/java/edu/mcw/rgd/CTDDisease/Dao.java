package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.dao.impl.AnnotationDAO;
import edu.mcw.rgd.dao.impl.GeneDAO;
import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dao.impl.XdbIdDAO;
import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.pipelines.PipelineSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by mtutaj on 10/13/2016.
 * <p>
 * wrapper for all calls to database
 */
public class Dao {

    Logger logAnnotsInserted = LogManager.getLogger("annots_inserted");
    Logger logAnnotsDeleted = LogManager.getLogger("annots_deleted");
    Logger logStatus = LogManager.getLogger("status");

    private AnnotationDAO annotationDAO = new AnnotationDAO();
    private GeneDAO geneDAO = new GeneDAO();
    private OntologyXDAO ontologyXDAO = new OntologyXDAO();
    private XdbIdDAO xdbIdDAO = new XdbIdDAO();
    private int createdBy;

    public String getConnectionInfo() {
        return xdbIdDAO.getConnectionInfo();
    }

    /**
     * get active genes with given external id
     *
     * @param xdbKey - external db key
     * @param accId  - external id to be looked for
     * @return list of Gene objects
     */
    public List<Gene> getGenesByXdbId(int xdbKey, String accId) throws Exception {
        String key = xdbKey + "|" + accId;
        List<Gene> genes = _geneCache.get(key);
        if (genes == null) {
            genes = xdbIdDAO.getActiveGenesByXdbId(xdbKey, accId);
            _geneCache.put(key, genes);
        }
        return genes;
    }

    Map<String, List<Gene>> _geneCache = new ConcurrentHashMap<>();

    /**
     * get disease terms matching given MESH ID
     *
     * @param meshId MESH ID
     * @return List of matching Term objects
     * @throws Exception if something wrong happens in spring framework
     */
    public List<Term> getDiseaseTermsByMeshID(String meshId) throws Exception {
        List<Term> terms = _termCache.get(meshId);
        if (terms == null) {
            terms = ontologyXDAO.getTermsBySynonym("RDO", meshId, "exact");
            _termCache.put(meshId, terms);
        }
        return terms;
    }

    Map<String, List<Term>> _termCache = new ConcurrentHashMap<>();

    /**
     * get orthologous genes for rat, mouse and human
     *
     * @param geneRgdId gene RGD id
     * @return orthologous genes
     * @throws Exception
     */
    public List<Gene> getOrthologs(int geneRgdId) throws Exception {

        List<Gene> orthologs = _orthologCache.get(geneRgdId);
        if (orthologs == null) {
            orthologs = geneDAO.getActiveOrthologs(geneRgdId);

            // leave only orthologs for rat, mouse and human
            Iterator<Gene> it = orthologs.iterator();
            while (it.hasNext()) {
                Gene gene = it.next();
                if (gene.getSpeciesTypeKey() != SpeciesType.RAT &&
                        gene.getSpeciesTypeKey() != SpeciesType.MOUSE &&
                        gene.getSpeciesTypeKey() != SpeciesType.HUMAN) {
                    it.remove();
                }
            }

            _orthologCache.put(geneRgdId, orthologs);
        }
        return orthologs;
    }

    Map<Integer, List<Gene>> _orthologCache = new ConcurrentHashMap<>();

    /**
     * get annotation key by a list of values that comprise unique key:
     * TERM_ACC+ANNOTATED_OBJECT_RGD_ID+REF_RGD_ID+EVIDENCE+WITH_INFO+QUALIFIER+XREF_SOURCE
     *
     * @param annot Annotation object with the following fields set: TERM_ACC+ANNOTATED_OBJECT_RGD_ID+REF_RGD_ID+EVIDENCE+WITH_INFO+QUALIFIER+XREF_SOURCE
     * @return value of annotation key or 0 if there is no such annotation
     * @throws Exception on spring framework dao failure
     */
    public int getAnnotationKey(Annotation annot) throws Exception {
        logAnnotsInserted.debug("KEY "+annot.dump("|"));
        return annotationDAO.getAnnotationKey(annot);
    }

    public int insertAnnotation(Annotation annot) throws Exception{
        logAnnotsInserted.debug("INSERT "+annot.dump("|"));
        return annotationDAO.insertAnnotation(annot);
    }

    /**
     * update last modified date to SYSDATE, and last modified by field for the annotation given full annot key
     * @param fullAnnotKey FULL_ANNOT_KEY
     * @return count of rows affected
     * @throws Exception on spring framework dao failure
     */
    public int updateLastModifiedDateForAnnot(int fullAnnotKey) throws Exception {
        return annotationDAO.updateLastModified(fullAnnotKey, getCreatedBy());
    }

    /**
     * get all RDO annotations modified by CTD disease pipeline before given date and time
     *
     * @return list of annotations
     * @throws Exception on spring framework dao failure
     */
    public List<Annotation> getAnnotationsModifiedBeforeTimestamp(Date dt) throws Exception{
        return annotationDAO.getAnnotationsModifiedBeforeTimestamp(createdBy, dt, "D");
    }

    public void deleteObsoleteAnnotations(PipelineSession session, Date time0, int originalAnnotCount,
        String staleAnnotDeleteThresholdStr) throws Exception {

        int staleAnnotDeleteThresholdPerc = Integer.parseInt(staleAnnotDeleteThresholdStr.substring(0, staleAnnotDeleteThresholdStr.length()-1));
        int staleAnnotDeleteThresholdCount = (staleAnnotDeleteThresholdPerc*originalAnnotCount) / 100;
        session.incrementCounter("OBSOLETE ANNOTATION "+staleAnnotDeleteThresholdStr+" DELETE THRESHOLD", staleAnnotDeleteThresholdCount);

        List<Annotation> obsoleteAnnotations = getAnnotationsModifiedBeforeTimestamp(time0);
        session.incrementCounter("OBSOLETE ANNOTATION COUNT", obsoleteAnnotations.size());

        if( obsoleteAnnotations.size() > staleAnnotDeleteThresholdCount ) {
            String msg = "WARN: OBSOLETE ANNOTATIONS NOT DELETED: "+staleAnnotDeleteThresholdStr+" THRESHOLD VIOLATED!";
            logStatus.info(msg);
            if( originalAnnotCount!=0 ) {
                session.incrementCounter(msg, staleAnnotDeleteThresholdCount);
            }
            return;
        }

        for( Annotation obsoleteAnnot: obsoleteAnnotations ) {
            logAnnotsDeleted.debug("DELETE " + obsoleteAnnot.dump("|"));
            session.incrementCounter("ANNOTS "+obsoleteAnnot.getEvidence() + " DELETED", 1);
        }

        annotationDAO.deleteAnnotations(getCreatedBy(), time0);
    }

    public void setCreatedBy(int createdBy) {
        this.createdBy = createdBy;
    }

    public int getCreatedBy() {
        return createdBy;
    }
}

