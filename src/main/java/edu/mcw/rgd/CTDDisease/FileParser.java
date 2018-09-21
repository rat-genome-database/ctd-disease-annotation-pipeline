package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.pipelines.PipelineRecord;
import edu.mcw.rgd.pipelines.RecordPreprocessor;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.GZIPInputStream;

/**
 * Created by mtutaj on 10/17/2016.
 */
public class FileParser extends RecordPreprocessor {
    private String ctdDiseaseFile;
    Logger log = LogManager.getLogger("status");

    @Override
    public void process() throws Exception {

        // download disease file from CTD
        String localFile = downloadDiseaseFile();

        // break file into lines
        BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(localFile))));
        String line;
        while( (line=reader.readLine())!=null ) {
            // skip comments
            if( line.startsWith("#") ) {
                continue;
            }

            getSession().incrementCounter("DATA LINES  READ", 1);
            Record rec = process(line);
            if( rec!=null ) {
                getSession().putRecordToFirstQueue(rec);
            }
        }
        reader.close();
    }

    public String downloadDiseaseFile() throws Exception {
        FileDownloader downloader = new FileDownloader();
        downloader.setExternalFile(getCtdDiseaseFile());
        downloader.setLocalFile("data/gene2disease.tsv.gz");
        downloader.setAppendDateStamp(true);
        String localFile = downloader.downloadNew();
        log.info("Downloaded file "+localFile);
        return localFile;
    }

    Record process(String line) throws Exception {

        // parse file
        // fields:
        // GeneSymbol	GeneID	DiseaseName	DiseaseID	DirectEvidence	InferenceChemicalName	InferenceScore	OmimIDs	PubMedIDs
        getSession().incrementCounter("DATA LINES  PROCESSED", 1);
        String[] cols = line.split("[\\t]", -1);
        String directEvidence = cols[4];
        if( Utils.isStringEmpty(directEvidence) ) {
            getSession().incrementCounter("DATA LINES INFERRED (SKIPPED)", 1);
            return null;
        }
        getSession().incrementCounter("DATA LINES WITH DIRECT EVIDENCE", 1);

        Record rec = new Record();
        rec.geneSymbol = cols[0];
        rec.geneID = cols[1];
        rec.diseaseName = cols[2];
        rec.diseaseID = cols[3];
        rec.directEvidence = directEvidence;
        rec.inferenceChemicalName = cols[5];
        rec.inferenceScore = Utils.isStringEmpty(cols[6]) ? null : Double.parseDouble(cols[6]);
        rec.omimIDs = cols[7];
        rec.pubMedIDs = cols[8];

        fixPubMedIds(rec);

        return rec;
    }

    void fixPubMedIds(Record rec) {
        if( !Utils.isStringEmpty(rec.pubMedIDs) ) {
            String[] pubMedIds = rec.pubMedIDs.split("[\\|]");
            // sort PubMed ids in ascending order
            Arrays.sort(pubMedIds, new Comparator<String>() {
                @Override
                public int compare(String o1, String o2) {
                    long r = Long.parseLong(o1) - Long.parseLong(o2);
                    return r<0 ? -1 : r>0 ? 1 : 0;
                }
            });
            // append 'PMID:' prefix to PubMed ids
            for( int i=0; i<pubMedIds.length; i++ ) {
                pubMedIds[i] = "PMID:"+pubMedIds[i];
            }
            // concatenate PubMed ids by '|'
            rec.pubMedIDs = Utils.concatenate(pubMedIds, "|");
        }
    }

    public void setCtdDiseaseFile(String ctdDiseaseFile) {
        this.ctdDiseaseFile = ctdDiseaseFile;
    }

    public String getCtdDiseaseFile() {
        return ctdDiseaseFile;
    }
}
