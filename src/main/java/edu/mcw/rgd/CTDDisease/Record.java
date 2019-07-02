package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.datamodel.Gene;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;

import java.util.ArrayList;
import java.util.List;

/**
 * @author mtutaj
 * @since 10/13/2016
 * incoming data record
 */
public class Record {

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
}
