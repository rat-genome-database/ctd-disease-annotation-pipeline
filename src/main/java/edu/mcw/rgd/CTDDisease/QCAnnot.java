package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.pipelines.PipelineRecord;
import edu.mcw.rgd.pipelines.RecordProcessor;

/**
 * @author mtutaj
 * <p>
 * Note: must be run in one thread only to avoid unique key violations
 */
public class QCAnnot extends RecordProcessor {

    private Dao dao;

    public void setDao(Dao dao) {
        this.dao = dao;
    }

    @Override
    public void process(PipelineRecord pipelineRecord) throws Exception {
        Record rec = (Record) pipelineRecord;
        rec.inRgdAnnots.clear();

        if( rec.incomingAnnots!=null ) {
            for (Annotation annot : rec.incomingAnnots) {
                rec.inRgdAnnots.add(dao.getAnnotationKey(annot));
            }
        }
    }
}
