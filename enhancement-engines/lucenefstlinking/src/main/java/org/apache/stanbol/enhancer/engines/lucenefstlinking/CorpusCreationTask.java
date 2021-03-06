/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.stanbol.enhancer.engines.lucenefstlinking;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import org.apache.commons.io.FileUtils;
import org.apache.lucene.index.IndexReader;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.RefCounted;
import org.opensextant.solrtexttagger.TaggerFstCorpus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime creation of FST corpora is done as {@link Callable}. This allows
 * users to decide by the configuration of the {@link ExecutorService} to
 * control how Corpora are build (e.g. how many can be built at a time.
 * @author Rupert Westenthaler
 *
 */
public class CorpusCreationTask implements Runnable{

    private final Logger log = LoggerFactory.getLogger(CorpusCreationTask.class);
    
    private final CorpusInfo fstInfo;
    private final IndexConfiguration indexConfig;
    private final long enqueued;
    
    public CorpusCreationTask(IndexConfiguration indexConfig, CorpusInfo fstInfo){
        if(indexConfig == null || fstInfo == null){
            throw new IllegalArgumentException("Pared parameters MUST NOT be NULL!");
        }
        this.indexConfig = indexConfig;
        this.fstInfo = fstInfo;
        this.enqueued = fstInfo.enqueue();
    }
    
    @Override
    public void run() {
        if(!indexConfig.isActive()){
            return; //task cancelled
        }
        //check if the FST corpus was enqueued a 2nd time
        if(enqueued != fstInfo.getEnqueued()){
            return;
        }
        SolrCore core = indexConfig.getIndex();
        if(core.isClosed()){
            log.warn("Unable to build {} becuase SolrCore {} is closed!",fstInfo,core.getName());
            return;
        }
        final TaggerFstCorpus corpus;
        RefCounted<SolrIndexSearcher> searcherRef = core.getSearcher();
        try { //STANBOL-1177: create FST models in AccessController.doPrivileged(..)
            final SolrIndexSearcher searcher = searcherRef.get();
            //we do get the AtomicReader, because TaggerFstCorpus will need it
            //anyways. This prevents to create another SlowCompositeReaderWrapper.
            final IndexReader reader = searcher.getAtomicReader();
            log.info(" ... build FST corpus for {}",fstInfo);
            corpus = AccessController.doPrivileged(new PrivilegedExceptionAction<TaggerFstCorpus>() {
                public TaggerFstCorpus run() throws IOException {
                    return new TaggerFstCorpus(reader, searcher.getIndexReader().getVersion(),
                        null, fstInfo.indexedField, fstInfo.storedField, fstInfo.analyzer,
                        fstInfo.partialMatches,1,100);
                }
            });
        } catch (PrivilegedActionException pae) {
            Exception e = pae.getException();
            if(e instanceof IOException){ //IO Exception while loading the file
                throw new IllegalStateException("Unable to read Information to build "
                        + fstInfo + " from SolrIndex '" + core.getName() + "'!", e);
            } else { //Runtime exception
                throw RuntimeException.class.cast(e);
            }
        } finally {
            searcherRef.decref(); //ensure that we dereference the searcher
        }
        if(indexConfig.isActive()){
            //set the created corpus to the FST Info
            fstInfo.setCorpus(enqueued, corpus);
            try { //STANBOL-1177: save FST models in AccessController.doPrivileged(..)
                AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                    public Object run() throws IOException {
                        if(fstInfo.fst.exists()){
                            if(!FileUtils.deleteQuietly(fstInfo.fst)){
                                log.warn("Unable to delete existing FST file for {}", fstInfo);
                            }
                        }
                        corpus.save(fstInfo.fst);
                        return null; //not used
                    }
                });
            } catch (PrivilegedActionException pae) {
                Exception e = pae.getException();
                if(e instanceof IOException){ //IO Exception while loading the file
                    log.warn("Unable to store FST corpus " + fstInfo + " to "
                            + fstInfo.fst.getAbsolutePath() + "!", e);
                } else { //Runtime exception
                    throw RuntimeException.class.cast(e);
                }
            }
        } //else index configuration no longer active ... ignore the built FST
    }
    
    @Override
    public String toString() {
        return new StringBuilder("Task: building ").append(fstInfo)
                .append(" for SolrCore ").append(indexConfig.getIndex().getName()).toString();
    }

}
