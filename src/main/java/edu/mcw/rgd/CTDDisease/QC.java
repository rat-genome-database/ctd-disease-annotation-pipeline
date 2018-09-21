package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.pipelines.PipelineRecord;
import edu.mcw.rgd.pipelines.RecordProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by mtutaj on 10/13/2016.
 */
public class QC extends RecordProcessor {

    Logger logGenesUnmatched = LogManager.getLogger("genes_unmatched");
    Logger logAnnotsSameAsOmim = LogManager.getLogger("annots_same_as_omim");

    private String srcPipeline;
    private int refRgdId;
    private String omimSrcPipeline;
    private int omimRefRgdId;

    private Dao dao;

    public void setDao(Dao dao) {
        this.dao = dao;
    }

    @Override
    public void process(PipelineRecord pipelineRecord) throws Exception {
        Record rec = (Record) pipelineRecord;

        if( rec.geneID!=null ) {
            qcGenes(rec);
            qcTerms(rec);
            qcAnnots(rec);
        }
    }

    public void qcGenes(Record rec) throws Exception {
        // match incoming data with a gene in RGD
        List<Gene> genes = dao.getGenesByXdbId(XdbId.XDB_KEY_NCBI_GENE, rec.geneID);
        if( genes.isEmpty() ) {
            getSession().incrementCounter("MATCH GENE NONE", 1);
            logGenesUnmatched.debug("GeneID:"+rec.geneID+" "+rec.geneSymbol);
            rec.incomingGene = null;
        } else {
            if (genes.size() == 1) {
                getSession().incrementCounter("MATCH GENE SINGLE", 1);
            } else {
                getSession().incrementCounter("MATCH GENE MULTIPLE", 1);
            }
            rec.incomingGene = genes.get(0);

            getSession().incrementCounter("MATCH GENE SPECIES "+ SpeciesType.getCommonName(rec.incomingGene.getSpeciesTypeKey()), 1);
        }
    }

    public void qcTerms(Record rec) throws Exception {
        // match incoming data with a term in RGD
        List<Term> diseaseTerms = dao.getDiseaseTermsByMeshID(rec.diseaseID);
        if( diseaseTerms.isEmpty() ) {
            getSession().incrementCounter("MATCH TERM NONE", 1);
            rec.incomingTerm = null;
        } else {
            if (diseaseTerms.size() == 1) {
                getSession().incrementCounter("MATCH TERM SINGLE", 1);
            } else {
                getSession().incrementCounter("MATCH TERM MULTIPLE", 1);
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
            getSession().incrementCounter("LINES WITH ANNOT SPLITS", 1);
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

        // see if this annotation has corresponding OMIM annotation
        annot.setDataSrc(getOmimSrcPipeline());
        annot.setRefRgdId(getOmimRefRgdId());
        annot.setEvidence("IAGP"); // primary evidence for OMIM annot
        int omimAnnotKey = dao.getAnnotationKey(annot);
        if( omimAnnotKey!=0 ) {
            logAnnotsSameAsOmim.debug(annot.dump("|"));
            getSession().incrementCounter("ANNOTS SAME AS PRIMARY OMIM SKIPPED", 1);
            return false;
        }
        annot.setEvidence("ISO"); // secondary evidence for OMIM annot
        omimAnnotKey = dao.getAnnotationKey(annot);
        if( omimAnnotKey!=0 ) {
            logAnnotsSameAsOmim.debug(annot.dump("|"));
            getSession().incrementCounter("ANNOTS SAME AS SECONDARY OMIM SKIPPED", 1);
            return false;
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
        getSession().incrementCounter("ANNOT "+evidence+" COUNT", 1);
        return true;
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
}
