package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.pipelines.PipelineRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by mtutaj on 10/13/2016.
 * <p>
 * unit of processing by the pipeline
 */
public class Record extends PipelineRecord {

    String geneSymbol;
    String geneID;
    String diseaseName;
    String diseaseID;
    String directEvidence;
    String inferenceChemicalName;
    Double inferenceScore;
    String omimIDs;
    String pubMedIDs;

    Gene incomingGene;
    Term incomingTerm;
    List<Annotation> incomingAnnots = new ArrayList<>();
    List<Integer> inRgdAnnots = new ArrayList<>();

    public Record() {
        setRecNo(++_recno);
    }
    static int _recno = 0;
}
