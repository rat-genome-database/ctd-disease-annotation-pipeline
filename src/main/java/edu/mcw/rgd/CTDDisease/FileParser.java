package edu.mcw.rgd.CTDDisease;

import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * @author mtutaj
 * @since 10/17/2016
 */
public class FileParser {
    private String ctdDiseaseFile;
    Logger log = LogManager.getLogger("status");

    public List<Record> process(CounterPool counters) throws Exception {

        List<Record> incomingRecords = new ArrayList<>();

        // download disease file from CTD
        String localFile = downloadDiseaseFile();

        // break file into lines
        BufferedReader reader = Utils.openReader(localFile);
        String line;
        while( (line=reader.readLine())!=null ) {
            // skip comments
            if( line.startsWith("#") ) {
                continue;
            }

            Record rec = process(line, counters);
            if( rec!=null ) {
                incomingRecords.add(rec);
            }
        }
        reader.close();

        return incomingRecords;
    }

    public String downloadDiseaseFile() throws Exception {
        FileDownloader downloader = new FileDownloader();
        downloader.setExternalFile(getCtdDiseaseFile());
        downloader.setLocalFile("data/gene2disease.tsv.gz");
        downloader.setAppendDateStamp(true);
        downloader.setSoTimeout(600000); // connection timeout set to 600s = 10min
        String localFile = downloader.downloadNew();
        log.info("Downloaded file "+localFile);
        return localFile;
    }

    Record process(String line, CounterPool counters) throws Exception {

        // parse file
        // fields:
        // GeneSymbol	GeneID	DiseaseName	DiseaseID	DirectEvidence	InferenceChemicalName	InferenceScore	OmimIDs	PubMedIDs
        counters.increment("DATA LINES  PROCESSED");
        String[] cols = line.split("[\\t]", -1);
        String directEvidence = cols[4];
        if( Utils.isStringEmpty(directEvidence) ) {
            counters.increment("DATA LINES INFERRED (SKIPPED)");
            return null;
        }
        counters.increment("DATA LINES WITH DIRECT EVIDENCE");

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
