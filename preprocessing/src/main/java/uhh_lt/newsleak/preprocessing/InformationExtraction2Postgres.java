package uhh_lt.newsleak.preprocessing;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.collection.CollectionProcessingEngine;
import org.apache.uima.collection.CollectionReaderDescription;
import org.apache.uima.fit.cpe.CpeBuilder;
import org.apache.uima.fit.examples.experiment.pos.XmiWriter;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.factory.CollectionReaderFactory;
import org.apache.uima.fit.factory.ExternalResourceFactory;
import org.apache.uima.resource.ExternalResourceDescription;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.Level;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import opennlp.uima.Sentence;
import opennlp.uima.Token;
import opennlp.uima.namefind.NameFinder;
import opennlp.uima.namefind.TokenNameFinderModelResourceImpl;
import opennlp.uima.postag.POSModelResourceImpl;
import opennlp.uima.postag.POSTagger;
import opennlp.uima.sentdetect.SentenceDetector;
import opennlp.uima.sentdetect.SentenceModelResourceImpl;
import opennlp.uima.tokenize.Tokenizer;
import opennlp.uima.tokenize.TokenizerModelResourceImpl;
import opennlp.uima.util.UimaUtil;
import uhh_lt.newsleak.annotator.DictionaryExtractor;
import uhh_lt.newsleak.annotator.HeidelTimeOpenNLP;
import uhh_lt.newsleak.annotator.KeytermExtractor;
import uhh_lt.newsleak.annotator.LanguageDetector;
import uhh_lt.newsleak.annotator.NerMicroservice;
import uhh_lt.newsleak.annotator.SentenceCleaner;
import uhh_lt.newsleak.annotator.SegmenterICU;
import uhh_lt.newsleak.reader.HooverElasticsearchReader;
import uhh_lt.newsleak.reader.NewsleakCsvStreamReader;
import uhh_lt.newsleak.reader.NewsleakElasticsearchReader;
import uhh_lt.newsleak.reader.NewsleakReader;
import uhh_lt.newsleak.resources.DictionaryResource;
import uhh_lt.newsleak.resources.ElasticsearchResource;
import uhh_lt.newsleak.resources.HooverResource;
import uhh_lt.newsleak.resources.LanguageDetectorResource;
import uhh_lt.newsleak.resources.PostgresResource;
import uhh_lt.newsleak.resources.TextLineWriterResource;
import uhh_lt.newsleak.writer.ElasticsearchDocumentWriter;
import uhh_lt.newsleak.writer.PostgresDbWriter;
import uhh_lt.newsleak.writer.TextLineWriter;

/**
 * Reads document.csv and metadata.csv, processes them in a UIMA pipeline
 * and writes output to an ElasticSearch index.
 *
 */
public class InformationExtraction2Postgres extends NewsleakPreprocessor
{

	public static void main(String[] args) throws Exception
	{

		InformationExtraction2Postgres np = new InformationExtraction2Postgres();
		np.getConfiguration(args);

		// run language detection
		np.pipelineLanguageDetection();
		// extract information (per language)
		np.pipelineAnnotation();

		// import metadata.csv
		np.initDb(np.dbName, np.dbUrl, np.dbUser, np.dbPass);
		np.metadataToPostgres();

		// create postgres indices
		String indexSql = FileUtils.readFileToString(new File(np.dbIndices)).replace("\n", "");
		try {
			st.executeUpdate(indexSql);
			np.logger.log(Level.INFO, "Index created");
		} catch (Exception e) {
			e.printStackTrace();
		}

		conn.close();
	}

	private void metadataToPostgres() {
		try {
			CopyManager cpManager = new CopyManager((BaseConnection) conn);
			st.executeUpdate("TRUNCATE TABLE metadata;");
			String metaFile = this.dataDirectory + File.separator + this.metadataFile;
			this.logger.log(Level.INFO, "Importing metadata from " + metaFile);
			Long n = cpManager.copyIn("COPY metadata FROM STDIN WITH CSV", new FileReader(metaFile));
			this.logger.log(Level.INFO, n + " metadata imported");
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	public CollectionReaderDescription getReader(String type) throws ResourceInitializationException {
		CollectionReaderDescription reader = null;
		if (type.equals("csv")) {
			reader = CollectionReaderFactory.createReaderDescription(
					NewsleakCsvStreamReader.class, this.typeSystem,
					NewsleakCsvStreamReader.PARAM_DOCUMENT_FILE, this.documentFile,
					NewsleakCsvStreamReader.PARAM_METADATA_FILE, this.metadataFile,
					NewsleakCsvStreamReader.PARAM_INPUTDIR, this.dataDirectory,
					NewsleakCsvStreamReader.PARAM_DEFAULT_LANG, this.defaultLanguage,
					NewsleakReader.PARAM_DEBUG_MAX_DOCS, this.debugMaxDocuments,
					NewsleakReader.PARAM_MAX_DOC_LENGTH, this.maxDocumentLength
					);
		} else if (type.equals("hoover")) {
			this.metadataFile = this.hooverTmpMetadata;
			ExternalResourceDescription hooverResource = ExternalResourceFactory.createExternalResourceDescription(
					HooverResource.class, 
					HooverResource.PARAM_HOST, this.hooverHost,
					HooverResource.PARAM_CLUSTERNAME, this.hooverClustername,
					HooverResource.PARAM_INDEX, this.hooverIndex,
					HooverResource.PARAM_PORT, this.hooverPort,
					HooverResource.PARAM_SEARCHURL, this.hooverSearchUrl
					);
			reader = CollectionReaderFactory.createReaderDescription(
					HooverElasticsearchReader.class, this.typeSystem,
					HooverElasticsearchReader.RESOURCE_HOOVER, hooverResource,
					HooverElasticsearchReader.RESOURCE_METADATA, this.getMetadataResourceDescription(),
					NewsleakReader.PARAM_DEBUG_MAX_DOCS, this.debugMaxDocuments,
					NewsleakReader.PARAM_MAX_DOC_LENGTH, this.maxDocumentLength
					);
		} else {
			this.logger.log(Level.SEVERE, "Unknown reader type: " + type);
			System.exit(1);
		}
		return reader;
	}


	public void pipelineLanguageDetection() throws Exception {
		statusListener = new NewsleakStatusCallbackListener(this.logger);

		// check for language support
		HashSet<String> supportedLanguages = LanguageDetector.getSupportedLanguages();
		for (String lang : this.processLanguages) {
			if (!supportedLanguages.contains(lang)) {
				logger.log(Level.SEVERE, "Language " + lang + " not supported (use ISO 639-3 codes)");
				System.exit(1);
			}
		}


		// reader
		CollectionReaderDescription reader = getReader(this.readerType);

		// language detection
		ExternalResourceDescription resourceLangDect = ExternalResourceFactory.createExternalResourceDescription(
				LanguageDetectorResource.class, 
				LanguageDetectorResource.PARAM_MODEL_FILE, "resources/langdetect-183.bin"
			    );
		AnalysisEngineDescription langDetect = AnalysisEngineFactory.createEngineDescription(
				LanguageDetector.class,
				LanguageDetector.MODEL_FILE, resourceLangDect,
				LanguageDetector.METADATA_FILE, this.getMetadataResourceDescription(),
				LanguageDetector.PARAM_DEFAULT_LANG, this.defaultLanguage,
				LanguageDetector.DOCLANG_FILE, "data/documentLanguages.ser"
				);

		// elasticsearch writer
		ExternalResourceDescription esResource = ExternalResourceFactory.createExternalResourceDescription(
				ElasticsearchResource.class, 
				ElasticsearchResource.PARAM_CREATE_INDEX, "true",
				ElasticsearchResource.PARAM_CLUSTERNAME, this.esClustername,
				ElasticsearchResource.PARAM_INDEX, this.esIndex,
				ElasticsearchResource.PARAM_HOST, this.esHost,
				ElasticsearchResource.PARAM_PORT, this.esPort,
				ElasticsearchResource.PARAM_DOCUMENT_MAPPING_FILE, "desc/elasticsearch_mapping_document_2.4.json");
		AnalysisEngineDescription esWriter = AnalysisEngineFactory.createEngineDescription(
				ElasticsearchDocumentWriter.class,
				ElasticsearchDocumentWriter.RESOURCE_ESCLIENT, esResource,
				ElasticsearchDocumentWriter.PARAM_PARAGRAPHS_AS_DOCUMENTS, this.paragraphsAsDocuments,
				ElasticsearchDocumentWriter.PARAM_MAX_DOC_LENGTH, this.maxDocumentLength
				);

		AnalysisEngineDescription ldPipeline = AnalysisEngineFactory.createEngineDescription(	
				langDetect,
				esWriter
				);

		CpeBuilder ldCpeBuilder = new CpeBuilder();
		ldCpeBuilder.setReader(reader);
		ldCpeBuilder.setMaxProcessingUnitThreadCount(this.threads);
		ldCpeBuilder.setAnalysisEngine(ldPipeline);
		CollectionProcessingEngine engine = ldCpeBuilder.createCpe(statusListener); 
		engine.process();

		while (statusListener.isProcessing()) {
			Thread.sleep(500);
		}
		
		
	}

	public void pipelineAnnotation() throws Exception {
		

		/* Proceeding for multi-language collections:
		 * - 1. run language detection and write language per document to ES index
		 * - 2. set document language for unsupported languages to default language
		 * - 3. run annotation pipeline per language with lang dependent resources
		 */
		
		// iterate over configured ISO-639-3 language codes
		boolean firstLanguage = true;
		for (String currentLanguage : processLanguages) {
			
			NewsleakStatusCallbackListener annotationListener = new NewsleakStatusCallbackListener(this.logger);

			Map<String, Locale> localeMap = LanguageDetector.localeToISO();
			Locale currentLocale = localeMap.get(currentLanguage);
			
			logger.log(Level.INFO, "Processing " + currentLocale.getDisplayName() + " (" + currentLanguage + ")");
			Thread.sleep(2000);

			// reader
			ExternalResourceDescription esResource = ExternalResourceFactory.createExternalResourceDescription(
					ElasticsearchResource.class, 
					ElasticsearchResource.PARAM_CREATE_INDEX, "false",
					ElasticsearchResource.PARAM_HOST, this.esHost,
					ElasticsearchResource.PARAM_CLUSTERNAME, this.esClustername,
					ElasticsearchResource.PARAM_INDEX, this.esIndex,
					ElasticsearchResource.PARAM_PORT, this.esPort,
					ElasticsearchResource.PARAM_DOCUMENT_MAPPING_FILE, "desc/elasticsearch_mapping_document_2.4.json");
			CollectionReaderDescription esReader = CollectionReaderFactory.createReaderDescription(
					NewsleakElasticsearchReader.class, this.typeSystem,
					NewsleakElasticsearchReader.RESOURCE_ESCLIENT, esResource,
					NewsleakElasticsearchReader.PARAM_LANGUAGE, currentLanguage
					);


			// sentences
			AnalysisEngineDescription sentenceICU = AnalysisEngineFactory.createEngineDescription(
					SegmenterICU.class,
					SegmenterICU.PARAM_LOCALE, currentLanguage
					);


			// sentence cleaner
			AnalysisEngineDescription sentenceCleaner = AnalysisEngineFactory.createEngineDescription(
					SentenceCleaner.class
					);


			// heideltime
			AnalysisEngineDescription heideltime = AnalysisEngineFactory.createEngineDescription(
					HeidelTimeOpenNLP.class,
					HeidelTimeOpenNLP.PARAM_LANGUAGE, "auto-" + currentLocale.getDisplayName().toLowerCase(),
					HeidelTimeOpenNLP.PARAM_LOCALE, "en_US"
					);

			
			AnalysisEngineDescription nerMicroservice = AnalysisEngineFactory.createEngineDescription(
					NerMicroservice.class,
					NerMicroservice.NER_SERVICE_URL, this.nerServiceUrl
					);

			// keyterms
			AnalysisEngineDescription keyterms = AnalysisEngineFactory.createEngineDescription(
					KeytermExtractor.class,
					KeytermExtractor.PARAM_N_KEYTERMS, 15,
					KeytermExtractor.PARAM_LANGUAGE_CODE, currentLanguage
					);

			// dictionaries
			ExternalResourceDescription dictResource = ExternalResourceFactory.createExternalResourceDescription(
					DictionaryResource.class, 
					DictionaryResource.PARAM_DATADIR, this.configDir + File.separator + "dictionaries",
					DictionaryResource.PARAM_DICTIONARY_FILES, this.dictionaryFiles,
					DictionaryResource.PARAM_LANGUAGE_CODE, currentLanguage);
			AnalysisEngineDescription dictionaries = AnalysisEngineFactory.createEngineDescription(
					DictionaryExtractor.class,
					DictionaryExtractor.RESOURCE_DICTIONARIES, dictResource
					);
			
			
			// writer
//			ExternalResourceDescription resourceLinewriter = ExternalResourceFactory.createExternalResourceDescription(
//					TextLineWriterResource.class, 
//					TextLineWriterResource.PARAM_OUTPUT_FILE, this.dataDirectory + File.separator + "output.txt");
//			AnalysisEngineDescription linewriter = AnalysisEngineFactory.createEngineDescription(
//					TextLineWriter.class,
//					TextLineWriter.RESOURCE_LINEWRITER, resourceLinewriter
//					);
//
//			AnalysisEngineDescription xmi = AnalysisEngineFactory.createEngineDescription(
//					XmiWriter.class,
//					XmiWriter.PARAM_OUTPUT_DIRECTORY, this.dataDirectory + File.separator + "xmi"
//					);

			ExternalResourceDescription resourcePostgres = ExternalResourceFactory.createExternalResourceDescription(
					PostgresResource.class, 
					PostgresResource.PARAM_DBURL, this.dbUrl,
					PostgresResource.PARAM_DBNAME, this.dbName,
					PostgresResource.PARAM_DBUSER, this.dbUser,
					PostgresResource.PARAM_DBPASS, this.dbPass,
					PostgresResource.PARAM_TABLE_SCHEMA, this.dbSchema,
					PostgresResource.PARAM_INDEX_SCHEMA, this.dbIndices,
					PostgresResource.PARAM_CREATE_DB, firstLanguage ? "true" : "false"
					);
			AnalysisEngineDescription postgresWriter = AnalysisEngineFactory.createEngineDescription(
					PostgresDbWriter.class,
					PostgresDbWriter.RESOURCE_POSTGRES, resourcePostgres
					);
			

			// define pipeline
			AnalysisEngineDescription pipeline = AnalysisEngineFactory.createEngineDescription(
					sentenceICU,
					sentenceCleaner,
					dictionaries,
					heideltime,
					nerMicroservice,
					keyterms,
					// linewriter,
					// xmi,
					postgresWriter
					);

			CpeBuilder cpeBuilder = new CpeBuilder();
			cpeBuilder.setReader(esReader);
			cpeBuilder.setMaxProcessingUnitThreadCount(this.threads);
			cpeBuilder.setAnalysisEngine(pipeline);

			// run processing
			CollectionProcessingEngine engine = cpeBuilder.createCpe(annotationListener);
			engine.process();

			while (annotationListener.isProcessing()) {
				// wait...
				Thread.sleep(1);
			}

			firstLanguage = false;

		}


	}

	public Properties getLanguageRescourceProperties(String language) {
		// config file
		Properties properties = new Properties();
		File resourceConfigFile = new File("resources" + File.separator + language, "resources.conf");
		try {
			InputStream input = new FileInputStream(resourceConfigFile);
			properties.load(input);

			readerType = properties.getProperty("datareader");

			input.close();
		}
		catch (IOException e) {
			System.err.println("Could not read resource configuration file " + resourceConfigFile.getPath());
			System.exit(1);
		}
		return properties;
	}

	private AnalysisEngineDescription getOpennlpNerAed(String shortType, String type, String modelFile) throws ResourceInitializationException {
		ExternalResourceDescription resourceNer = ExternalResourceFactory.createExternalResourceDescription(
				TokenNameFinderModelResourceImpl.class, new File(modelFile));
		AnalysisEngineDescription ner = AnalysisEngineFactory.createEngineDescription(
				NameFinder.class,
				UimaUtil.MODEL_PARAMETER, resourceNer,
				UimaUtil.SENTENCE_TYPE_PARAMETER, Sentence.class,
				UimaUtil.TOKEN_TYPE_PARAMETER, Token.class,
				NameFinder.NAME_TYPE_PARAMETER, type
				);
		return ner;
	}

}
