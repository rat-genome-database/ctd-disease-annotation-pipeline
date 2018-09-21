package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.pipelines.PipelineRecord;
import edu.mcw.rgd.pipelines.RecordProcessor;

/**
 * Created by mtutaj on 10/17/2016.
 */
public class Loader extends RecordProcessor {
    private Dao dao;

    public void setDao(Dao dao) {
        this.dao = dao;
    }

    @Override
    public void process(PipelineRecord pipelineRecord) throws Exception {
        Record rec = (Record) pipelineRecord;

        if( rec.incomingAnnots!=null ) {
            for (int i = 0; i < rec.incomingAnnots.size(); i++) {
                int annotKeyInRgd = rec.inRgdAnnots.get(i);
                if (annotKeyInRgd != 0) {
                    dao.updateLastModifiedDateForAnnot(annotKeyInRgd);
                    getSession().incrementCounter("ANNOTS UP-TO-DATE", 1);
                } else {
                    dao.insertAnnotation(rec.incomingAnnots.get(i));
                    getSession().incrementCounter("ANNOTS INSERTED", 1);
                }
            }
        }
    }
}
