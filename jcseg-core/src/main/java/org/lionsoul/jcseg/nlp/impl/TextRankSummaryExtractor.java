package org.lionsoul.jcseg.nlp.impl;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lionsoul.jcseg.Sentence;
import org.lionsoul.jcseg.SentenceSeg;
import org.lionsoul.jcseg.core.ISegment;
import org.lionsoul.jcseg.core.IWord;
import org.lionsoul.jcseg.nlp.SummaryExtractor;
import org.lionsoul.jcseg.util.Sort;

/**
 * TextRank summary extractor base on textRank algorithm
 * 
 * @author chenxin<chenxin619315@gmail.com>
*/
public class TextRankSummaryExtractor extends SummaryExtractor
{
	//const factor
	public static final float D  = 0.85F;
	public static final float K1 = 2.0F;
	public static final float B = 0.75F;
	
	//default keywords number
	protected int sentenceNum = 6;
	
	//max iterate times
	protected int maxIterateNum = 120;
	

	public TextRankSummaryExtractor(ISegment wordSeg, SentenceSeg sentenceSeg) {
		super(wordSeg, sentenceSeg);
	}
	
	/**
	 * text doc to sentence
	 * 
	 * @param	reader
	 * @return	List<Sentence>
	 * @throws	IOException 
	*/
	List<Sentence> textToSentence(Reader reader) throws IOException
	{
		List<Sentence> sentence = new ArrayList<Sentence>();
		
		Sentence sen = null;
		sentenceSeg.reset(reader);
		while ( (sen = sentenceSeg.next()) != null )
		{
			sentence.add(sen);
		}
		
		return sentence;
	}
	
	/**
	 * sentence to words
	 * 
	 * @param	sentence
	 * @return	List<List<IWord>>
	 * @throws	IOException 
	*/
	List<List<IWord>> sentenceTokenize(List<Sentence> sentence) throws IOException
	{
		List<List<IWord>> senWords = new ArrayList<List<IWord>>();
		for ( Sentence sen : sentence )
		{
			List<IWord> words = new ArrayList<IWord>();
			wordSeg.reset(new StringReader(sen.getValue()));
			IWord word = null;
			while ( (word = wordSeg.next()) != null )
			{
				words.add(word);
			}
			
			senWords.add(words);
		}
		
		return senWords;
	}
	
	/**
	 * degree of correlations matrix build
	 * well, base on BM25 alogrithm: 
	 * Score(Q,d) = sigema(IDF(qi)*fi(k1+1)/(fi+k1*(1-b+dl/avgdl)))
	 * IDF(qi) = log((N-n(qi)+0.5)/(n(qi)+0.5))
	 * 
	 * @param	sentence
	 * @param	sendWords
	 * @return	double[]
	*/
	double[][] BM25RelevanceMatixBuild(
			List<Sentence> sentence, List<List<IWord>> senWords)
	{
		int docNum = sentence.size();
		
		//1. count the average document length
		double avgdl = 0D;
		for ( Sentence sen : sentence ) avgdl += sen.getLength();
		avgdl /= docNum;
		
		//2. count all the tf and the df/N
		//word and the frequency mapping for each document 
		@SuppressWarnings("unchecked")
		Map<IWord, Integer>[] tf = new Map[docNum];
		//word and the number of document thats contains this word mapping 
		Map<IWord, Integer> df = new HashMap<IWord, Integer>();
		int index = 0;
		for ( List<IWord> words : senWords )
		{
			Map<IWord, Integer> f = new HashMap<IWord, Integer>();
			for ( IWord word : words )
			{
				f.put(word, f.containsKey(word) ? f.get(word) + 1 : 1);
			}
			
			tf[index++] = f;
			
			/*
			 * the logic inner the folling loop could not merge in the above one
			 * is becase that same word may appear more than one time in a document,
			 * well, map will clear up the duplicate. 
			*/
			for ( Map.Entry<IWord, Integer> entry : f.entrySet() )
			{
				IWord key = entry.getKey();
				df.put(key, df.containsKey(key) ? df.get(key) + 1 : 1);
			}
		}
		
		//3. count the words idf
		Map<IWord, Double> idf = new HashMap<IWord, Double>();
		for ( Map.Entry<IWord, Integer> entry : df.entrySet() )
		{
			IWord key = entry.getKey();
			int nq = df.get(key).intValue();
			idf.put(key, Math.log((docNum - nq + 0.5) / (nq + 0.5)) );
		}
		
		//4. build the relevance score matrix
		double[][] scores = new double[docNum][docNum];
		for ( int i = 0; i < docNum; i++ )
		{
			int j = 0;
			int dl = sentence.get(i).getLength();
			double dlRelative = K1 * (1 - B + B * dl / avgdl);
			
			/*
			 * count the relevance for document sentence[i]
			 * with all the queries setence[i]  
			*/
			for ( List<IWord> query : senWords )
			{
				double score = 0F;
				for ( IWord q : query )
				{
					/*
					 * count the relevance of q with document sentence[i] 
					*/
					int fi = tf[i].containsKey(q) ? tf[i].get(q).intValue() : 0;
					double rel = fi * (K1 + 1) / (fi + dlRelative);
					
					/*
					 * count and add the sub relevance value: idf * rel 
					*/
					score += idf.get(q).doubleValue() * rel;
				}
				
				scores[i][j++] = score;
			}
		}
		
		//let gc do its work
		for ( Map<IWord, Integer> m : tf ) m.clear();
		tf = null;
		
		df.clear(); df = null;
		idf.clear(); idf = null;
		
		return scores;
	}
	
	/**
	 * sum the specifield double array
	 * 
	 * @param	score
	 * @return	double
	*/
	static double sum(double[] score)
	{
		double r = 0D;
		for ( double d : score ) r += d;
		return r;
	}

	@Override
	public List<String> getKeySentence(Reader reader) throws IOException 
	{
		//build the documents
		List<Sentence> sentence = textToSentence(reader);
		List<List<IWord>> senWords = sentenceTokenize(sentence);
		int docNum = sentence.size();
	
		//documents relavance matrix build
		double[][] relevance = BM25RelevanceMatixBuild(sentence, senWords);
		//org.lionsoul.jcseg.util.Util.printMatrix(relevance);
		
		double[] score = new double[docNum];
		double[] weight_sum = new double[docNum];
		for ( int i = 0; i < docNum; i++ )
		{
			weight_sum[i] = sum(relevance[i]) - relevance[i][i];
			score[i] = 0;
		}
		
		//do the textrank score iteration
		for ( int c = 0; c < maxIterateNum; c++ )
		{
			for ( int i = 0; i < docNum; i++ )
			{
				double sigema = 0D;
				for ( int j = 0; j < docNum; j++ )
				{
					if ( i == j || weight_sum[j] == 0 ) continue;
					/*
					 * ws(vj) * wji / sigema(wjk) with k in Out(Vj) 
					 * ws(vj): score[j] the score of document[j]
					 * wji: relevance score bettween document[j] and document[i]
					 * sigema(wjk): weight sum for document[j]
					*/
					sigema += relevance[j][i] / weight_sum[j] * score[j];
				}
				
				score[i] = 1 - D + D * sigema;
			}
		}
		
		//build the document set
		//and sort the documents by scores
		Document[] docs = new Document[docNum];
		for ( int i = 0; i < docNum; i++ ) {
			docs[i] = new Document(sentence.get(i), senWords.get(i), score[i]);
		}
		
		Sort.shellSort(docs);
		//return the sublist as the final result
		int len = Math.min(sentenceNum, docNum);
		List<String> topSentence = new ArrayList<String>(len);
		for ( int i = 0; i < len; i++ ) {
			topSentence.add(docs[i].getSentence().getValue());
			System.out.println(i+", "+docs[i].getScore()+", "+docs[i].getSentence());
		}
		
		//let gc do its works
		sentence.clear(); sentence = null;
		senWords.clear(); senWords = null;
		relevance = null; score = null;
		weight_sum = null; docs = null;
		
		return topSentence;
	}

	@Override
	public String getSummary(Reader reader) throws IOException {
		return null;
	}
	
	
	/**
	 * summary document inner class
	 * 
	 * @author	chenxin<chenxin619315@gmail.com>
	*/
	public class Document implements Comparable<Document>
	{
		/**
		 * the relevance score for the current document 
		*/
		private double score;
		
		/**
		 * the orginal sentence for the ducment 
		*/
		private Sentence sentence;
		
		/**
		 * the Words list for the current document 
		*/
		private List<IWord> words;
		
		/**
		 * construct method 
		 * 
		 * @param	sentence
		 * @param	words
		 * @param	score
		*/
		public Document(Sentence sentence, List<IWord> words, double score)
		{
			this.sentence = sentence;
			this.words = words;
			this.score = score;
		}

		public double getScore() {
			return score;
		}

		public void setScore(double score) {
			this.score = score;
		}

		public Sentence getSentence() {
			return sentence;
		}

		public void setSentence(Sentence sentence) {
			this.sentence = sentence;
		}

		public List<IWord> getWords() {
			return words;
		}

		public void setWords(List<IWord> words) {
			this.words = words;
		}

		/**
		 * override the compareTo method
		 * compare document with its relevance score
		 * 
		 * @param	Document
		*/
		@Override
		public int compareTo(Document o) 
		{
			double v = o.getScore() - score;
			if ( v > 0 ) return 1;
			if ( v < 0 ) return -1;
			return 0;
		}
	}
	
}