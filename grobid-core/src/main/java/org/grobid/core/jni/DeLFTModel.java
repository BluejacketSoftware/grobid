package org.grobid.core.jni;

import org.grobid.core.GrobidModel;
import org.grobid.core.GrobidModels;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.utilities.GrobidProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;  
import java.io.*;
import java.lang.StringBuilder;
import java.util.*;

import jep.Jep;
import jep.JepConfig;
import jep.JepException;

import java.util.function.Consumer;

/**
 * 
 * @author: Patrice
 */
public class DeLFTModel {
    public static final Logger LOGGER = LoggerFactory.getLogger(DeLFTModel.class);

    // Exploit JNI CPython interpreter to execute load and execute a DeLFT deep learning model 
    private String modelName;

    public DeLFTModel(GrobidModel model) {
        this.modelName = model.getModelName().replace("-", "_");
        System.out.println(this.modelName);
        try {
            LOGGER.info("Loading DeLFT model for " + model.getModelName() + "...");
            JEPThreadPool.getInstance().run(new InitModel(this.modelName, GrobidProperties.getInstance().getModelPath()));
        } catch(InterruptedException e) {
            LOGGER.error("DeLFT model " + this.modelName + " initialization failed", e);
        }
    }

    class InitModel implements Runnable { 
        private String modelName;
        private File modelPath;
          
        public InitModel(String modelName, File modelPath) { 
            this.modelName = modelName;
            this.modelPath = modelPath;
        } 
          
        @Override
        public void run() { 
            Jep jep = JEPThreadPool.getInstance().getJEPInstance(); 
            try { 
                jep.eval(this.modelName+" = sequenceLabelling.Sequence('" + this.modelName.replace("_", "-") + "')");
                jep.eval(this.modelName+".load(dir_path='"+modelPath.getAbsolutePath()+"')");
            } catch(JepException e) {
                LOGGER.error("DeLFT model initialization failed", e);
            } 
        } 
    } 

    private class LabelTask implements Callable<String> { 
        private String data;
        private String modelName;

        public LabelTask(String modelName, String data) { 
            System.out.println("label thread: " + Thread.currentThread().getId());
            this.modelName = modelName;
            this.data = data;
        } 
          
        @Override
        public String call() { 
            Jep jep = JEPThreadPool.getInstance().getJEPInstance(); 
            StringBuilder labelledData = new StringBuilder();
            try {
                //System.out.println(this.data);
                jep.set("input", this.data);
                jep.eval("x_all, f_all = load_data_crf_string(input)");
                Object objectResults = jep.getValue(this.modelName+".tag(x_all, None)");
                ArrayList<ArrayList<List<String>>> results = (ArrayList<ArrayList<List<String>>>)objectResults;

                // inject the tags
                BufferedReader bufReader = new BufferedReader(new StringReader(data));
                String line;
                int i = 0; // sentence index
                int j = 0; // word index in the sentence
                ArrayList<List<String>> result = results.get(0);
                while( (line=bufReader.readLine()) != null ) {
                    line = line.trim();
                    if ((line.length() == 0) && (j != 0)) {
                        j = 0;
                        i++;
                        if (i == results.size())
                            break;
                        result = results.get(i);
                        continue;
                    }
                    if (line.length() == 0)
                        continue;
                    labelledData.append(line);
                    labelledData.append(" ");
                    List<String> pair = result.get(j);
                    // first is the token, second is the label (DeLFT format)
                    String token = pair.get(0);
                    String label = pair.get(1);
                    labelledData.append(DeLFTModel.delft2grobidLabel(label));
                    labelledData.append("\n");
                    j++;
                }
                
                jep.eval("del input");
                jep.eval("del x_all");
                jep.eval("del f_all");
                //jep.eval("K.clear_session()");
            } catch(JepException e) {
                LOGGER.error("DeLFT model labelling via JEP failed", e);
            } catch(IOException e) {
                LOGGER.error("DeLFT model labelling failed", e);
            }
            //System.out.println(labelledData.toString());
            return labelledData.toString();
        } 
    } 

    public String label(String data) {
        String result = null;
        try {
            result = JEPThreadPool.getInstance().call(new LabelTask(this.modelName, data));
        } catch(InterruptedException e) {
            LOGGER.error("DeLFT model " + this.modelName + " labelling interrupted", e);
        } catch(ExecutionException e) {
            LOGGER.error("DeLFT model " + this.modelName + " labelling failed", e);
        }
        return result;
    }

    public static void trainJNI(String modelName, File trainingData, File outputModel) {
        try {
            LOGGER.info("Train DeLFT model " + modelName + "...");
            JEPThreadPool.getInstance().run(new TrainTask(modelName, trainingData, GrobidProperties.getInstance().getModelPath()));
        } catch(InterruptedException e) {
            LOGGER.error("Train DeLFT model " + modelName + " task failed", e);
        }
    }

    private static class TrainTask implements Runnable { 
        private String modelName;
        private File trainPath;
        private File modelPath;

        public TrainTask(String modelName, File trainPath, File modelPath) { 
            System.out.println("train thread: " + Thread.currentThread().getId());
            this.modelName = modelName;
            this.trainPath = trainPath;
            this.modelPath = modelPath;
        } 
          
        @Override
        public void run() { 
            Jep jep = JEPThreadPool.getInstance().getJEPInstance(); 
            try {
                // load data
                jep.eval("x_all, y_all, f_all = load_data_and_labels_crf_file('" + this.trainPath.getAbsolutePath() + "')");
                jep.eval("x_train, x_valid, y_train, y_valid = train_test_split(x_all, y_all, test_size=0.1)");
                jep.eval("print(len(x_train), 'train sequences')");
                jep.eval("print(len(x_valid), 'validation sequences')");

                // init model to be trained
                jep.eval("model = sequenceLabelling.Sequence('"+this.modelName+
                    "', max_epoch=100, recurrent_dropout=0.50, embeddings_name='glove-840B', use_ELMo=False)");

                // actual training
                //start_time = time.time()
                jep.eval("model.train(x_train, y_train, x_valid, y_valid)");
                //runtime = round(time.time() - start_time, 3)
                //print("training runtime: %s seconds " % (runtime))

                // saving the model
                System.out.println(this.modelPath.getAbsolutePath());
                jep.eval("model.save('"+this.modelPath.getAbsolutePath()+"')");
                
                // cleaning
                jep.eval("del x_all");
                jep.eval("del y_all");
                jep.eval("del f_all");
                jep.eval("del x_train");
                jep.eval("del x_valid");
                jep.eval("del y_train");
                jep.eval("del y_valid");
                jep.eval("del model");
            } catch(JepException e) {
                LOGGER.error("DeLFT model training via JEP failed", e);
            } /*catch(IOException e) {
                LOGGER.error("DeLFT model train failed", e);
            }*/
        } 
    } 

    /**
     *  Train with an external process rather than with JNI, this approach appears to be more stable for the
     *  training process (JNI approach hangs after a while) and does not raise any runtime/integration issues. 
     */
    public static void train(String modelName, File trainingData, File outputModel) {
        try {
            LOGGER.info("Train DeLFT model " + modelName + "...");
            ProcessBuilder pb = new ProcessBuilder("python3", 
                "grobidTagger.py", 
                modelName,
                "train",
                "--out", GrobidProperties.getInstance().getModelPath().getAbsolutePath());
            pb.directory(new File(GrobidProperties.getInstance().getDeLFTPath()));
            Process process = pb.start(); 
            pb.inheritIO();
            SimpleStreamGobbler streamGobbler = 
                new SimpleStreamGobbler(process.getInputStream(), System.out::println);
            Executors.newSingleThreadExecutor().submit(streamGobbler);
            streamGobbler = new SimpleStreamGobbler(process.getErrorStream(), System.err::println);
            Executors.newSingleThreadExecutor().submit(streamGobbler);
            int exitCode = process.waitFor();
            //assert exitCode == 0;
        } catch(IOException e) {
            LOGGER.error("IO error when training DeLFT model " + modelName, e);
        } catch(InterruptedException e) {
            LOGGER.error("Train DeLFT model " + modelName + " task failed", e);
        }
    }

    public synchronized void close() {
        try {
            LOGGER.info("Close DeLFT model " + this.modelName + "...");
            JEPThreadPool.getInstance().run(new CloseModel(this.modelName));
        } catch(InterruptedException e) {
            LOGGER.error("Close DeLFT model " + this.modelName + " task failed", e);
        }
    }

    private class CloseModel implements Runnable { 
        private String modelName;
          
        public CloseModel(String modelName) { 
            this.modelName = modelName;
        } 
          
        @Override
        public void run() { 
            Jep jep = JEPThreadPool.getInstance().getJEPInstance(); 
            try { 
                jep.eval("del "+this.modelName);
            } catch(JepException e) {
                LOGGER.error("Closing DeLFT model failed", e);
            } 
        } 
    }

    private static String delft2grobidLabel(String label) {
        if (label.equals("O")) {
            label = "<other>";
        } else if (label.startsWith("B-")) {
            label = label.replace("B-", "I-");
        } else if (label.startsWith("I-")) {
            label = label.replace("I-", "");
        } 
        return label;
    }

    private static class SimpleStreamGobbler implements Runnable {
        private InputStream inputStream;
        private Consumer<String> consumer;
     
        public SimpleStreamGobbler(InputStream inputStream, Consumer<String> consumer) {
            this.inputStream = inputStream;
            this.consumer = consumer;
        }
     
        @Override
        public void run() {
            new BufferedReader(new InputStreamReader(inputStream)).lines()
              .forEach(consumer);
        }
    }

}