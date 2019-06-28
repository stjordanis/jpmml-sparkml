/*
 * Copyright (c) 2018 Villu Ruusmann
 *
 * This file is part of JPMML-SparkML
 *
 * JPMML-SparkML is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-SparkML is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-SparkML.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.sparkml;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.bind.JAXBException;

import com.google.common.collect.Iterables;
import org.apache.spark.ml.PipelineModel;
import org.apache.spark.ml.PipelineStage;
import org.apache.spark.ml.Transformer;
import org.apache.spark.ml.linalg.Vector;
import org.apache.spark.ml.param.shared.HasPredictionCol;
import org.apache.spark.ml.param.shared.HasProbabilityCol;
import org.apache.spark.ml.regression.GeneralizedLinearRegressionModel;
import org.apache.spark.ml.tuning.CrossValidatorModel;
import org.apache.spark.ml.tuning.TrainValidationSplitModel;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.StructType;
import org.dmg.pmml.DerivedField;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MiningFunction;
import org.dmg.pmml.MiningSchema;
import org.dmg.pmml.Output;
import org.dmg.pmml.OutputField;
import org.dmg.pmml.PMML;
import org.dmg.pmml.ResultFeature;
import org.dmg.pmml.VerificationField;
import org.jpmml.converter.Feature;
import org.jpmml.converter.ModelUtil;
import org.jpmml.converter.mining.MiningModelUtil;
import org.jpmml.model.MetroJAXBUtil;

public class PMMLBuilder {

	private StructType schema = null;

	private PipelineModel pipelineModel = null;

	private Map<RegexKey, Map<String, Object>> options = new LinkedHashMap<>();

	private Verification verification = null;


	public PMMLBuilder(StructType schema, PipelineModel pipelineModel){
		setSchema(schema);
		setPipelineModel(pipelineModel);
	}

	public PMML build(){
		StructType schema = getSchema();
		PipelineModel pipelineModel = getPipelineModel();
		Map<RegexKey, ? extends Map<String, ?>> options = getOptions();
		Verification verification = getVerification();

		ConverterFactory converterFactory = new ConverterFactory(options);

		SparkMLEncoder encoder = new SparkMLEncoder(schema, converterFactory);

		Map<FieldName, DerivedField> derivedFields = encoder.getDerivedFields();

		List<org.dmg.pmml.Model> models = new ArrayList<>();

		List<String> predictionColumns = new ArrayList<>();
		List<String> probabilityColumns = new ArrayList<>();

		// Transformations preceding the last model
		List<FieldName> preProcessorNames = Collections.emptyList();

		Iterable<Transformer> transformers = getTransformers(pipelineModel);
		for(Transformer transformer : transformers){
			TransformerConverter<?> converter = converterFactory.newConverter(transformer);

			if(converter instanceof FeatureConverter){
				FeatureConverter<?> featureConverter = (FeatureConverter<?>)converter;

				featureConverter.registerFeatures(encoder);
			} else

			if(converter instanceof ModelConverter){
				ModelConverter<?> modelConverter = (ModelConverter<?>)converter;

				org.dmg.pmml.Model model = modelConverter.registerModel(encoder);

				models.add(model);

				hasPredictionCol:
				if(transformer instanceof HasPredictionCol){
					HasPredictionCol hasPredictionCol = (HasPredictionCol)transformer;

					// XXX
					if((transformer instanceof GeneralizedLinearRegressionModel) && (MiningFunction.CLASSIFICATION).equals(model.getMiningFunction())){
						break hasPredictionCol;
					}

					predictionColumns.add(hasPredictionCol.getPredictionCol());
				} // End if

				if(transformer instanceof HasProbabilityCol){
					HasProbabilityCol hasProbabilityCol = (HasProbabilityCol)transformer;

					probabilityColumns.add(hasProbabilityCol.getProbabilityCol());
				}

				preProcessorNames = new ArrayList<>(derivedFields.keySet());
			} else

			{
				throw new IllegalArgumentException("Expected a " + FeatureConverter.class.getName() + " or " + ModelConverter.class.getName() + " instance, got " + converter);
			}
		}

		// Transformations following the last model
		List<FieldName> postProcessorNames = new ArrayList<>(derivedFields.keySet());
		postProcessorNames.removeAll(preProcessorNames);

		org.dmg.pmml.Model model;

		if(models.size() == 1){
			model = Iterables.getOnlyElement(models);
		} else

		if(models.size() > 1){
			model = MiningModelUtil.createModelChain(models);
		} else

		{
			throw new IllegalArgumentException("Expected a pipeline with one or more models, got a pipeline with zero models");
		}

		for(FieldName postProcessorName : postProcessorNames){
			DerivedField derivedField = derivedFields.get(postProcessorName);

			encoder.removeDerivedField(postProcessorName);

			Output output = ModelUtil.ensureOutput(model);

			OutputField outputField = new OutputField(derivedField.getName(), derivedField.getOpType(), derivedField.getDataType())
				.setResultFeature(ResultFeature.TRANSFORMED_VALUE)
				.setExpression(derivedField.getExpression());

			output.addOutputFields(outputField);
		}

		PMML pmml = encoder.encodePMML(model);

		if((predictionColumns.size() > 0 || probabilityColumns.size() > 0) && (verification != null)){
			Dataset<Row> dataset = verification.getDataset();
			Dataset<Row> transformedDataset = verification.getTransformedDataset();
			Double precision = verification.getPrecision();
			Double zeroThreshold = verification.getZeroThreshold();

			List<String> inputColumns = new ArrayList<>();

			MiningSchema miningSchema = model.getMiningSchema();

			List<MiningField> miningFields = miningSchema.getMiningFields();
			for(MiningField miningField : miningFields){
				MiningField.UsageType usageType = miningField.getUsageType();

				switch(usageType){
					case ACTIVE:
						FieldName name = miningField.getName();

						inputColumns.add(name.getValue());
						break;
					default:
						break;
				}
			}

			Map<VerificationField, List<?>> data = new LinkedHashMap<>();

			for(String inputColumn : inputColumns){
				VerificationField verificationField = ModelUtil.createVerificationField(FieldName.create(inputColumn));

				data.put(verificationField, getColumn(dataset, inputColumn));
			}

			for(String predictionColumn : predictionColumns){
				Feature feature = encoder.getOnlyFeature(predictionColumn);

				VerificationField verificationField = ModelUtil.createVerificationField(feature.getName())
					.setPrecision(precision)
					.setZeroThreshold(zeroThreshold);

				data.put(verificationField, getColumn(transformedDataset, predictionColumn));
			}

			for(String probabilityColumn : probabilityColumns){
				List<Feature> features = encoder.getFeatures(probabilityColumn);

				for(int i = 0; i < features.size(); i++){
					Feature feature = features.get(i);

					VerificationField verificationField = ModelUtil.createVerificationField(feature.getName())
						.setPrecision(precision)
						.setZeroThreshold(zeroThreshold);

					data.put(verificationField, getVectorColumn(transformedDataset, probabilityColumn, i));
				}
			}

			model.setModelVerification(ModelUtil.createModelVerification(data));
		}

		return pmml;
	}

	public byte[] buildByteArray(){
		return buildByteArray(1024 * 1024);
	}

	private byte[] buildByteArray(int size){
		PMML pmml = build();

		ByteArrayOutputStream os = new ByteArrayOutputStream(size);

		try {
			MetroJAXBUtil.marshalPMML(pmml, os);
		} catch(JAXBException je){
			throw new RuntimeException(je);
		}

		return os.toByteArray();
	}

	public File buildFile(File file) throws IOException {
		PMML pmml = build();

		OutputStream os = new FileOutputStream(file);

		try {
			MetroJAXBUtil.marshalPMML(pmml, os);
		} catch(JAXBException je){
			throw new RuntimeException(je);
		} finally {
			os.close();
		}

		return file;
	}

	public PMMLBuilder putOption(String key, Object value){
		return putOptions(Collections.singletonMap(key, value));
	}

	public PMMLBuilder putOptions(Map<String, ?> map){
		return putOptions(Pattern.compile(".*"), map);
	}

	public PMMLBuilder putOption(PipelineStage pipelineStage, String key, Object value){
		return putOptions(pipelineStage, Collections.singletonMap(key, value));
	}

	public PMMLBuilder putOptions(PipelineStage pipelineStage, Map<String, ?> map){
		return putOptions(Pattern.compile(pipelineStage.uid(), Pattern.LITERAL), map);
	}

	public PMMLBuilder putOptions(Pattern pattern, Map<String, ?> map){
		Map<RegexKey, Map<String, Object>> options = getOptions();

		RegexKey key = new RegexKey(pattern);

		Map<String, Object> patternOptions = options.get(key);
		if(patternOptions == null){
			patternOptions = new LinkedHashMap<>();

			options.put(key, patternOptions);
		}

		patternOptions.putAll(map);

		return this;
	}

	public PMMLBuilder verify(Dataset<Row> dataset){
		return verify(dataset, 1e-14, 1e-14);
	}

	public PMMLBuilder verify(Dataset<Row> dataset, double precision, double zeroThreshold){
		PipelineModel pipelineModel = getPipelineModel();

		Dataset<Row> transformedDataset = pipelineModel.transform(dataset);

		Verification verification = new Verification(dataset, transformedDataset)
			.setPrecision(precision)
			.setZeroThreshold(zeroThreshold);

		return setVerification(verification);
	}

	public StructType getSchema(){
		return this.schema;
	}

	public PMMLBuilder setSchema(StructType schema){

		if(schema == null){
			throw new IllegalArgumentException();
		}

		this.schema = schema;

		return this;
	}

	public PipelineModel getPipelineModel(){
		return this.pipelineModel;
	}

	public PMMLBuilder setPipelineModel(PipelineModel pipelineModel){

		if(pipelineModel == null){
			throw new IllegalArgumentException();
		}

		this.pipelineModel = pipelineModel;

		return this;
	}

	public Map<RegexKey, Map<String, Object>> getOptions(){
		return this.options;
	}

	private PMMLBuilder setOptions(Map<RegexKey, Map<String, Object>> options){

		if(options == null){
			throw new IllegalArgumentException();
		}

		this.options = options;

		return this;
	}

	public Verification getVerification(){
		return this.verification;
	}

	private PMMLBuilder setVerification(Verification verification){
		this.verification = verification;

		return this;
	}

	static
	private Iterable<Transformer> getTransformers(PipelineModel pipelineModel){
		List<Transformer> result = new ArrayList<>();
		result.add(pipelineModel);

		Function<Transformer, List<Transformer>> function = new Function<Transformer, List<Transformer>>(){

			@Override
			public List<Transformer> apply(Transformer transformer){

				if(transformer instanceof PipelineModel){
					PipelineModel pipelineModel = (PipelineModel)transformer;

					return Arrays.asList(pipelineModel.stages());
				} else

				if(transformer instanceof CrossValidatorModel){
					CrossValidatorModel crossValidatorModel = (CrossValidatorModel)transformer;

					return Collections.singletonList(crossValidatorModel.bestModel());
				} else

				if(transformer instanceof TrainValidationSplitModel){
					TrainValidationSplitModel trainValidationSplitModel = (TrainValidationSplitModel)transformer;

					return Collections.singletonList(trainValidationSplitModel.bestModel());
				}

				return null;
			}
		};

		while(true){
			boolean modified = false;

			ListIterator<Transformer> transformerIt = result.listIterator();
			while(transformerIt.hasNext()){
				Transformer transformer = transformerIt.next();

				List<Transformer> childTransformers = function.apply(transformer);
				if(childTransformers != null){
					transformerIt.remove();

					for(Transformer childTransformer : childTransformers){
						transformerIt.add(childTransformer);
					}

					modified = true;
				}
			}

			if(!modified){
				break;
			}
		}

		return result;
	}

	static
	private List<?> getColumn(Dataset<Row> dataset, String name){
		List<Row> rows = dataset.select(name)
			.collectAsList();

		return rows.stream()
			.map(row -> row.apply(0))
			.collect(Collectors.toList());
	}

	static
	private List<?> getVectorColumn(Dataset<Row> dataset, String name, int index){
		List<Vector> column = (List<Vector>)getColumn(dataset, name);

		return column.stream()
			.map(vector -> vector.apply(index))
			.collect(Collectors.toList());
	}

	static
	private void init(){
		ConverterFactory.checkVersion();
		ConverterFactory.checkApplicationClasspath();
		ConverterFactory.checkNoShading();
	}

	static
	public class Verification {

		private Dataset<Row> dataset = null;

		private Dataset<Row> transformedDataset = null;

		public Double precision = null;

		public Double zeroThreshold = null;


		private Verification(Dataset<Row> dataset, Dataset<Row> transformedDataset){
			setDataset(dataset);
			setTransformedDataset(transformedDataset);
		}

		public Dataset<Row> getDataset(){
			return this.dataset;
		}

		private Verification setDataset(Dataset<Row> dataset){
			this.dataset = dataset;

			return this;
		}

		public Dataset<Row> getTransformedDataset(){
			return this.transformedDataset;
		}

		private Verification setTransformedDataset(Dataset<Row> transformedDataset){
			this.transformedDataset = transformedDataset;

			return this;
		}

		public Double getPrecision(){
			return this.precision;
		}

		public Verification setPrecision(Double precision){
			this.precision = precision;

			return this;
		}

		public Double getZeroThreshold(){
			return this.zeroThreshold;
		}

		public Verification setZeroThreshold(Double zeroThreshold){
			this.zeroThreshold = zeroThreshold;

			return this;
		}
	}

	static {
		init();
	}
}