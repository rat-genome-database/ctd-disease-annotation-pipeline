package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by mtutaj on 10/13/2016.
 */
public class QC {

    Logger logGenesUnmatched = LogManager.getLogger("genes_unmatched");
    Logger logAnnotsSameAsOmim = LogManager.getLogger("annots_same_as_omim");
    Logger logStatus = LogManager.getLogger("status");

    private String srcPipeline;
    private int refRgdId;
    private String omimSrcPipeline;
    private int omimRefRgdId;

    private Dao dao;
    private CounterPool counters;

    public void setDao(Dao dao) {
        this.dao = dao;
    }

    public void process(Record rec) throws Exception {

        if( rec.geneID==null ) {
            return;
        }

        final int MAX_RETRY_COUNT = 3;
        for( int attemptNr=0; attemptNr<MAX_RETRY_COUNT; attemptNr++ ) {

            // sometimes duplicate keys when inserting new annots cause a problem
            try {
                qcGenes(rec);
                qcTerms(rec);
                qcAnnots(rec);
                loadData(rec);

                return;
            } catch(Exception e) {
                logStatus.debug("=== attempt "+(attemptNr+1)+" out of "+MAX_RETRY_COUNT+": record processing failed: ");
                ByteArrayOutputStream bs = new ByteArrayOutputStream();
                e.printStackTrace(new PrintStream(bs));
                logStatus.debug(bs.toString());

                counters.increment("RETRIED_AFTER_EXCEPTION");
            }
        }

        throw new Exception("ERROR: reached MAX_RETRY_COUNT="+MAX_RETRY_COUNT);
    }

    public void qcGenes(Record rec) throws Exception {
        // match incoming data with a gene in RGD
        List<Gene> genes = dao.getGenesByXdbId(XdbId.XDB_KEY_NCBI_GENE, rec.geneID);
        if( genes.isEmpty() ) {
            counters.increment("MATCH GENE NONE");
            logGenesUnmatched.debug("GeneID:"+rec.geneID+" "+rec.geneSymbol);
            rec.incomingGene = null;
        } else {
            if (genes.size() == 1) {
                counters.increment("MATCH GENE SINGLE");
            } else {
                counters.increment("MATCH GENE MULTIPLE");
            }
            rec.incomingGene = genes.get(0);

            counters.increment("MATCH GENE SPECIES "+ SpeciesType.getCommonName(rec.incomingGene.getSpeciesTypeKey()));
        }
    }

    public void qcTerms(Record rec) throws Exception {
        // match incoming data with a term in RGD
        List<Term> diseaseTerms = dao.getDiseaseTermsByMeshID(rec.diseaseID);
        if( diseaseTerms.isEmpty() ) {
            counters.increment("MATCH TERM NONE");
            rec.incomingTerm = null;
        } else {
            if (diseaseTerms.size() == 1) {
                counters.increment("MATCH TERM SINGLE");
            } else {
                counters.increment("MATCH TERM MULTIPLE");
                System.out.println("MULTI TERMS: "+rec.diseaseID+" "+ Utils.concatenate("|", diseaseTerms, "getAccId"));
            }
            rec.incomingTerm = diseaseTerms.get(0);
        }
    }

    public void qcAnnots(Record rec) throws Exception {

        // determine if annotation can be created
        if( rec.incomingGene==null || rec.incomingTerm==null ) {
            return;
        }
        rec.incomingAnnots.clear();

        List<String> xrefSources = getXRefSources(rec.pubMedIDs);
        for( String xrefSource: xrefSources ) {
            if( !createAnnotation(rec, rec.incomingGene, "EXP", xrefSource) ) {
                continue; // this CTD annot is the same as OMIM annot -- skip it
            }

            List<Gene> orthologs = dao.getOrthologs(rec.incomingGene.getRgdId());
            for (Gene gene : orthologs) {
                createAnnotation(rec, gene, "ISO", xrefSource);
            }
        }


        rec.inRgdAnnots.clear();

        if( rec.incomingAnnots!=null ) {
            for (Annotation annot : rec.incomingAnnots) {
                rec.inRgdAnnots.add(dao.getAnnotationKey(annot));
            }
        }
    }

    /**
     * We cannot store a string of pubmed id list longer than 4000 chars in the database,
     * so we split them into multiple chunks, of up to 4000 chars long
     * @param pubMedIds string of pubmed ids
     * @return List of 4000-char long chunks with pubmed ids
     */
    public List<String> getXRefSources(String pubMedIds) {
        List<String> chunks = new ArrayList<>();
        while( pubMedIds.length()>4000 ) {
            int barPos = pubMedIds.lastIndexOf('|', 4000);
            chunks.add(pubMedIds.substring(0, barPos));
            pubMedIds = pubMedIds.substring(barPos+1);
        }
        if( !chunks.isEmpty() ) {
            counters.increment("LINES WITH ANNOT SPLITS");
        }

        // add the last chunk
        chunks.add(pubMedIds);
        return chunks;
    }

    /**
     * create annotation from incoming data
     * @param rec Record object
     * @param gene
     * @param evidence
     * @param xrefSource
     * @throws Exception
     */
    public boolean createAnnotation(Record rec, Gene gene, String evidence, String xrefSource) throws Exception {

        String notes = "CTD Direct Evidence: "+rec.directEvidence;

        Annotation annot = new Annotation();
        annot.setAnnotatedObjectRgdId(gene.getRgdId());
        annot.setAspect("D");
        annot.setObjectName(gene.getName());
        annot.setObjectSymbol(gene.getSymbol());
        annot.setRgdObjectKey(RgdId.OBJECT_KEY_GENES);
        annot.setTerm(rec.incomingTerm.getTerm());
        annot.setTermAcc(rec.incomingTerm.getAccId());
        annot.setXrefSource(xrefSource);
        annot.setNotes(notes);
        if( gene.getRgdId()!=rec.incomingGene.getRgdId() ) {
            annot.setWithInfo("RGD:"+rec.incomingGene.getRgdId());
        }

        // as of July 18, 2023, we no longer skip CTD annotations that are the same as OMIM annots
        if( false ) {
            if( annotationIsSameAsOmim(annot) ) {
                return false;
            }
        }

        annot.setDataSrc(getSrcPipeline());
        annot.setRefRgdId(getRefRgdId());
        annot.setEvidence(evidence);
        annot.setCreatedBy(dao.getCreatedBy());
        annot.setLastModifiedBy(dao.getCreatedBy());
        annot.setCreatedDate(new Date());
        annot.setLastModifiedDate(annot.getCreatedDate());

        // add this annotation to incoming annotations
        rec.incomingAnnots.add(annot);
        counters.increment("CTD ANNOT   "+evidence+" COUNT");
        return true;
    }

    boolean annotationIsSameAsOmim( Annotation annot ) throws Exception {
        // see if this annotation has corresponding OMIM annotation
        annot.setDataSrc(getOmimSrcPipeline());
        annot.setRefRgdId(getOmimRefRgdId());
        annot.setEvidence("IAGP"); // primary evidence for OMIM annot
        int omimAnnotKey = dao.getAnnotationKey(annot);
        if (omimAnnotKey != 0) {
            logAnnotsSameAsOmim.debug(annot.dump("|"));
            counters.increment("CTD ANNOTS SAME AS PRIMARY OMIM SKIPPED");
            return true;
        }
        annot.setEvidence("ISO"); // secondary evidence for OMIM annot
        omimAnnotKey = dao.getAnnotationKey(annot);
        if (omimAnnotKey != 0) {
            logAnnotsSameAsOmim.debug(annot.dump("|"));
            counters.increment("CTD ANNOTS SAME AS SECONDARY OMIM SKIPPED");
            return true;
        }

        return false;
    }

    void loadData(Record rec) throws Exception {
        if( rec.incomingAnnots!=null ) {
            for (int i = 0; i < rec.incomingAnnots.size(); i++) {
                int annotKeyInRgd = rec.inRgdAnnots.get(i);
                if (annotKeyInRgd != 0) {
                    dao.updateLastModifiedDateForAnnot(annotKeyInRgd);
                    counters.increment("CTD ANNOTS UP-TO-DATE");
                } else {
                    dao.insertAnnotation(rec.incomingAnnots.get(i));
                    counters.increment("CTD ANNOTS INSERTED");
                }
            }
        }
    }

    public void setSrcPipeline(String srcPipeline) {
        this.srcPipeline = srcPipeline;
    }

    public String getSrcPipeline() {
        return srcPipeline;
    }

    public void setRefRgdId(int refRgdId) {
        this.refRgdId = refRgdId;
    }

    public int getRefRgdId() {
        return refRgdId;
    }

    public void setOmimSrcPipeline(String OmimSrcPipeline) {
        omimSrcPipeline = OmimSrcPipeline;
    }

    public String getOmimSrcPipeline() {
        return omimSrcPipeline;
    }

    public void setOmimRefRgdId(int OmimRefRgdId) {
        omimRefRgdId = OmimRefRgdId;
    }

    public int getOmimRefRgdId() {
        return omimRefRgdId;
    }

    public CounterPool getCounters() {
        return counters;
    }

    public void setCounters(CounterPool counters) {
        this.counters = counters;
    }
}
