/*
 * Copyright (c) 2013-2015 Cinchapi Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cinchapi.concourse.server;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.cinchapi.concourse.thrift.Diff;
import org.apache.thrift.TException;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.server.TThreadPoolServer.Args;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportException;
import org.cinchapi.concourse.annotate.Alias;
import org.cinchapi.concourse.annotate.Atomic;
import org.cinchapi.concourse.annotate.AutoRetry;
import org.cinchapi.concourse.annotate.Batch;
import org.cinchapi.concourse.annotate.HistoricalRead;
import org.cinchapi.concourse.annotate.VersionControl;
import org.cinchapi.concourse.lang.ConjunctionSymbol;
import org.cinchapi.concourse.lang.Expression;
import org.cinchapi.concourse.lang.NaturalLanguage;
import org.cinchapi.concourse.lang.Parser;
import org.cinchapi.concourse.lang.PostfixNotationSymbol;
import org.cinchapi.concourse.lang.Symbol;
import org.cinchapi.concourse.lang.Language;
import org.cinchapi.concourse.plugin.ConcourseRuntime;
import org.cinchapi.concourse.plugin.Storage;
import org.cinchapi.concourse.security.AccessManager;
import org.cinchapi.concourse.server.http.HttpServer;
import org.cinchapi.concourse.server.io.FileSystem;
import org.cinchapi.concourse.server.jmx.ConcourseServerMXBean;
import org.cinchapi.concourse.server.jmx.ManagedOperation;
import org.cinchapi.concourse.server.storage.AtomicOperation;
import org.cinchapi.concourse.server.storage.AtomicStateException;
import org.cinchapi.concourse.server.storage.BufferedStore;
import org.cinchapi.concourse.server.storage.Compoundable;
import org.cinchapi.concourse.server.storage.Engine;
import org.cinchapi.concourse.server.storage.Store;
import org.cinchapi.concourse.server.storage.Transaction;
import org.cinchapi.concourse.server.storage.TransactionStateException;
import org.cinchapi.concourse.shell.CommandLine;
import org.cinchapi.concourse.thrift.AccessToken;
import org.cinchapi.concourse.thrift.ConcourseService;
import org.cinchapi.concourse.thrift.TCriteria;
import org.cinchapi.concourse.thrift.TObject;
import org.cinchapi.concourse.thrift.ConcourseService.Iface;
import org.cinchapi.concourse.thrift.Operator;
import org.cinchapi.concourse.thrift.TSymbol;
import org.cinchapi.concourse.thrift.TransactionToken;
import org.cinchapi.concourse.time.Time;
import org.cinchapi.concourse.util.Convert;
import org.cinchapi.concourse.util.Convert.ResolvableLink;
import org.cinchapi.concourse.util.DataServices;
import org.cinchapi.concourse.util.Environments;
import org.cinchapi.concourse.util.Logger;
import org.cinchapi.concourse.util.TCollections;
import org.cinchapi.concourse.util.TSets;
import org.cinchapi.concourse.util.Timestamps;
import org.cinchapi.concourse.util.Version;
import org.cinchapi.concourse.Constants;
import org.cinchapi.concourse.DuplicateEntryException;
import org.cinchapi.concourse.Link;
import org.cinchapi.concourse.ParseException;
import org.cinchapi.concourse.Timestamp;
import org.cinchapi.concourse.SecurityException;
import org.cinchapi.concourse.TransactionException;
import org.cinchapi.concourse.thrift.Type;
import org.cliffc.high_scale_lib.NonBlockingHashMap;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import static org.cinchapi.concourse.server.GlobalState.*;

/**
 * Accepts requests from clients to read and write data in Concourse. The server
 * is configured with a {@code concourse.prefs} file.
 * 
 * @author Jeff Nelson
 */
public class ConcourseServer implements ConcourseRuntime, ConcourseServerMXBean {

    /**
     * Run the server...
     * 
     * @param args
     * @throws TTransportException
     * @throws MalformedObjectNameException
     * @throws NotCompliantMBeanException
     * @throws MBeanRegistrationException
     * @throws InstanceAlreadyExistsException
     */
    public static void main(String... args) throws TTransportException,
            MalformedObjectNameException, InstanceAlreadyExistsException,
            MBeanRegistrationException, NotCompliantMBeanException {
        final ConcourseServer server = new ConcourseServer();

        // Ensure the application is properly configured
        MemoryUsage heap = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage();
        if(heap.getInit() < MIN_HEAP_SIZE) {
            System.err.println("Cannot initialize Concourse Server with "
                    + "a heap smaller than " + MIN_HEAP_SIZE + " bytes");
            System.exit(127);
        }

        // Register MXBean
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(
                "org.cinchapi.concourse.server.jmx:type=ConcourseServerMXBean");
        mbs.registerMBean(server, name);

        // Start the server...
        Thread serverThread = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    CommandLine.displayWelcomeBanner();
                    server.start();
                }
                catch (TTransportException e) {
                    e.printStackTrace();
                    System.exit(-1);
                }
            }

        }, "main");
        serverThread.start();

        // Prepare for graceful shutdown...
        // NOTE: It may be necessary to run the Java VM with
        // -Djava.net.preferIPv4Stack=true
        final Thread shutdownThread = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    ServerSocket socket = new ServerSocket(SHUTDOWN_PORT);
                    socket.accept(); // block until a shutdown request is made
                    Logger.info("Shutdown request received");
                    server.stop();
                    socket.close();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }

            }

        }, "Shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();

        // "Warm up" the ANTLR parsing engine in the background
        new Thread(new Runnable() {

            @Override
            public void run() {
                NaturalLanguage.parseMicros("now");
            }

        }).start();

        // Add a shutdown hook that launches the official {@link ShutdownRunner}
        // in cases where the server process is directly killed (i.e. from the
        // control script)
        Runtime.getRuntime().addShutdownHook(new Thread() {

            @Override
            public void run() {
                ShutdownRunner.main();
                try {
                    shutdownThread.join();
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        });
    }

    /**
     * Add {@code key} as {@code value} in {@code record} using the atomic
     * {@code operation} if the record is empty. Otherwise, throw an
     * {@link AtomicStateException}.
     * <p>
     * If another operation adds data to the record after the initial check,
     * then an {@link AtomicStateException} will be thrown when an attempt is
     * made to commit {@code operation}.
     * </p>
     * 
     * @param key
     * @param value
     * @param record
     * @param operation
     * @throws AtomicStateException
     */
    private static void addIfEmptyAtomic(String key, TObject value,
            long record, AtomicOperation operation) throws AtomicStateException {
        if(!operation.contains(record)) {
            operation.add(key, value, record);
        }
        else {
            throw AtomicStateException.RETRY;
        }
    }

    /**
     * Do the work to chronologize (generate a chronology of values) for
     * {@code key} in {@code record}. If {@code history} and {@code result} are
     * not {@code null}, then this method will only update the chronology with
     * the latest changes since the history/result were calculated.
     * 
     * @param key
     * @param record
     * @param result
     * @param history
     * @param atomic
     */
    private static void chronologizeAtomic(String key, long record,
            Map<Long, Set<TObject>> result, Map<Long, String> history,
            AtomicOperation atomic) {
        Map<Long, String> latest = atomic.audit(key, record);
        if(latest.size() > history.size()) {
            for (int i = history.size(); i < latest.size(); ++i) {
                long timestamp = Iterables.get(
                        (Iterable<Long>) latest.keySet(), i);
                Set<TObject> values = atomic.select(key, record, timestamp);
                if(!values.isEmpty()) {
                    result.put(timestamp, values);
                }
            }
        }
    }

    /**
     * Remove all the values mapped from the {@code key} in {@code record} using
     * the specified {@code atomic} operation.
     * 
     * @param key
     * @param record
     * @param atomic
     */
    private static void clearKeyRecordAtomic(String key, long record,
            AtomicOperation atomic) {
        Set<TObject> values = atomic.select(key, record);
        for (TObject value : values) {
            atomic.remove(key, value, record);
        }
    }

    /**
     * Do the work to remove all the data from {@code record} using the
     * specified {@code atomic} operation.
     * 
     * @param record
     * @param atomic
     */
    private static void clearRecordAtomic(long record, AtomicOperation atomic) {
        Map<String, Set<TObject>> values = atomic.select(record);
        for (Map.Entry<String, Set<TObject>> entry : values.entrySet()) {
            String key = entry.getKey();
            Set<TObject> valueSet = entry.getValue();
            for (TObject value : valueSet) {
                atomic.remove(key, value, record);
            }
        }
    }

    /**
     * Parse the thrift represented {@code criteria} into an {@link Queue} of
     * {@link PostfixNotationSymbol postfix notation symbols} that can be used
     * within the {@link #findAtomic(Queue, Deque, AtomicOperation)} method.
     * 
     * @param criteria
     * @return
     */
    private static Queue<PostfixNotationSymbol> convertCriteriaToQueue(
            TCriteria criteria) {
        List<Symbol> symbols = Lists.newArrayList();
        for (TSymbol tsymbol : criteria.getSymbols()) {
            symbols.add(Language.translateFromThriftSymbol(tsymbol));
        }
        Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(symbols);
        return queue;
    }

    /**
     * Do the work to jsonify (dump to json string) each of the {@code records},
     * possibly at {@code timestamp} (if it is greater than 0) using the
     * {@code store}.
     * 
     * @param records
     * @param timestamp
     * @param identifier - will include the primary key for each record in the
     *            dump, if set to {@code true}
     * @param store
     * @return the json string dump
     */
    private static String doJsonify(List<Long> records, long timestamp,
            boolean identifier, Store store) {
        JsonArray array = new JsonArray();
        for (long record : records) {
            Map<String, Set<TObject>> data = timestamp == 0 ? store
                    .select(record) : store.select(record, timestamp);
            JsonElement object = DataServices.gson().toJsonTree(data);
            if(identifier) {
                object.getAsJsonObject().addProperty(
                        GlobalState.JSON_RESERVED_IDENTIFIER_NAME, record);
            }
            array.add(object);
        }
        return array.size() == 1 ? array.get(0).toString() : array.toString();
    }

    /**
     * Perform a ping of the {@code record} (e.g check to see if the record
     * currently has any data) from the perspective of the specified
     * {@code store}.
     * 
     * @param record
     * @param store
     * @return {@code true} if the record currently has any data
     */
    private static boolean doPing(long record, Store store) {
        return !store.describe(record).isEmpty();
    }

    /**
     * Do the work necessary to complete a complex find operation based on the
     * {@code queue} of symbols.
     * <p>
     * This method does not return a value. If you need to perform a complex
     * find using an {@link AtomicOperation} and immediately get the results,
     * then you should pass an empty stack into this method and then pop the
     * results after the method executes.
     * 
     * <pre>
     * Queue&lt;PostfixNotationSymbol&gt; queue = Parser.toPostfixNotation(ccl);
     * Deque&lt;Set&lt;Long&gt;&gt; stack = new ArrayDeque&lt;Set&lt;Long&gt;&gt;();
     * findAtomic(queue, stack, atomic)
     * Set&lt;Long&gt; matches = stack.pop();
     * </pre>
     * 
     * </p>
     * 
     * @param queue - The criteria/ccl represented as a queue in postfix
     *            notation. Use {@link Parser#toPostfixNotation(List)} or
     *            {@link Parser#toPostfixNotation(String)} or
     *            {@link #convertCriteriaToQueue(TCriteria)} to get this value.
     *            This is modified in place.
     * @param stack - A stack that contains Sets of records that match the
     *            corresponding criteria branches in the {@code queue}. This is
     *            modified in-place.
     * @param atomic - The atomic operation
     */
    private static void findAtomic(Queue<PostfixNotationSymbol> queue,
            Deque<Set<Long>> stack, AtomicOperation atomic) {
        // NOTE: there is room to do some query planning/optimization by going
        // through the pfn and plotting an Abstract Syntax Tree and looking for
        // the optimal routes to start with
        Preconditions.checkArgument(stack.isEmpty());
        for (PostfixNotationSymbol symbol : queue) {
            if(symbol == ConjunctionSymbol.AND) {
                stack.push(TSets.intersection(stack.pop(), stack.pop()));
            }
            else if(symbol == ConjunctionSymbol.OR) {
                stack.push(TSets.union(stack.pop(), stack.pop()));
            }
            else if(symbol instanceof Expression) {
                Expression exp = (Expression) symbol;
                stack.push(exp.getTimestampRaw() == 0 ? atomic.find(
                        exp.getKeyRaw(), exp.getOperatorRaw(),
                        exp.getValuesRaw()) : atomic.find(
                        exp.getTimestampRaw(), exp.getKeyRaw(),
                        exp.getOperatorRaw(), exp.getValuesRaw()));
            }
            else {
                // If we reach here, then the conversion to postfix notation
                // failed :-/
                throw new IllegalStateException();
            }
        }
    }

    /**
     * Find data matching the criteria described by the {@code queue} or insert
     * each of the {@code objects} into a new record. Either way, place the
     * records that match the criteria or that contain the inserted data into
     * {@code records}.
     * 
     * @param records - the collection that holds the records that either match
     *            the criteria or hold the inserted objects.
     * @param objects - a list of Multimaps, each of which containing data to
     *            insert into a distinct record. Get this using the
     *            {@link Convert#anyJsonToJava(String)} method.
     * @param queue - the parsed criteria attained from
     *            {@link #convertCriteriaToQueue(TCriteria)} or
     *            {@link Parser#toPostfixNotation(String)}.
     * @param stack - a stack (usually empty) that is used while processing the
     *            query
     * @param atomic - the atomic operation through which all operations are
     *            conducted
     */
    private static void findOrInsertAtomic(Set<Long> records,
            List<Multimap<String, Object>> objects,
            Queue<PostfixNotationSymbol> queue, Deque<Set<Long>> stack,
            AtomicOperation atomic) {
        findAtomic(queue, stack, atomic);
        records.addAll(stack.pop());
        if(records.isEmpty()) {
            List<DeferredWrite> deferred = Lists.newArrayList();
            for (Multimap<String, Object> object : objects) {
                long record = Time.now();
                atomic.touch(record);
                if(insertAtomic(object, record, atomic, deferred)) {
                    records.add(record);
                }
                else {
                    throw AtomicStateException.RETRY;
                }
            }
            insertDeferredAtomic(deferred, atomic);
        }
    }

    /**
     * Do the work to atomically insert all of the {@code data} into
     * {@code record} and return {@code true} if the operation is successful.
     * 
     * @param data
     * @param record
     * @param atomic
     * @param deferred
     * @return {@code true} if all the data is atomically inserted
     */
    private static boolean insertAtomic(Multimap<String, Object> data,
            long record, AtomicOperation atomic, List<DeferredWrite> deferred) {
        for (String key : data.keySet()) {
            if(key.equals(Constants.JSON_RESERVED_IDENTIFIER_NAME)) {
                continue;
            }
            for (Object value : data.get(key)) {
                if(value instanceof ResolvableLink) {
                    deferred.add(new DeferredWrite(key, value, record));
                }
                else if(!atomic.add(key, Convert.javaToThrift(value), record)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Atomically insert a list of {@link DeferredWrite deferred writes}. This
     * method should only be called after all necessary calls to
     * {@link #insertAtomic(Multimap, long, AtomicOperation, List)} have been
     * made.
     * 
     * @param deferred
     * @param atomic
     * @return {@code true} if all the writes are successful
     */
    private static boolean insertDeferredAtomic(List<DeferredWrite> deferred,
            AtomicOperation atomic) {
        // NOTE: The validity of the key in each deferred write is assumed to
        // have already been checked
        for (DeferredWrite write : deferred) {
            if(write.getValue() instanceof ResolvableLink) {
                ResolvableLink rlink = (ResolvableLink) write.getValue();
                Queue<PostfixNotationSymbol> queue = Parser
                        .toPostfixNotation(rlink.getCcl());
                Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                findAtomic(queue, stack, atomic);
                Set<Long> targets = stack.pop();
                for (long target : targets) {
                    if(target == write.getRecord()) {
                        // Here, if the target and source are the same, we skip
                        // instead of failing because we assume that the caller
                        // is
                        // using a complex resolvable link criteria that
                        // accidentally creates self links.
                        continue;
                    }
                    TObject link = Convert.javaToThrift(Link.to(target));
                    if(!atomic.add(write.getKey(), link, write.getRecord())) {
                        return false;
                    }
                }
            }
            else if(!atomic.add(write.getKey(),
                    Convert.javaToThrift(write.getValue()), write.getRecord())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Return {@code true} if adding {@code link} to {@code record} is valid.
     * This method is used to enforce referential integrity (i.e. record cannot
     * link to itself) before the data makes it way to the Engine.
     * 
     * @param link
     * @param record
     * @return {@code true} if the link is valid
     */
    private static boolean isValidLink(Link link, long record) {
        return link.longValue() != record;
    }

    /**
     * Revert {@code key} in {@code record} to its state {@code timestamp} using
     * the provided atomic {@code operation}.
     * 
     * @param key
     * @param record
     * @param timestamp
     * @param operation
     * @throws AtomicStateException
     */
    private static void revertAtomic(String key, long record, long timestamp,
            AtomicOperation operation) throws AtomicStateException {
        Set<TObject> past = operation.select(key, record, timestamp);
        Set<TObject> present = operation.select(key, record);
        Set<TObject> xor = Sets.symmetricDifference(past, present);
        for (TObject value : xor) {
            if(present.contains(value)) {
                operation.remove(key, value, record);
            }
            else {
                operation.add(key, value, record);
            }
        }
    }

    /**
     * Contains the credentials used by the {@link #accessManager}. This file is
     * typically located in the root of the server installation.
     */
    private static final String ACCESS_FILE = ".access";

    private static final int MIN_HEAP_SIZE = 268435456; // 256 MB

    private static final int NUM_WORKER_THREADS = 100; // This may become
                                                       // configurable in a
                                                       // prefs file in a
                                                       // future release.

    /**
     * The base location where the indexed buffer pages are stored.
     */
    private final String bufferStore;

    /**
     * The base location where the indexed database records are stored.
     */
    private final String dbStore;

    /**
     * A mapping from env to the corresponding Engine that controls all the
     * logic for data storage and retrieval.
     */
    private final Map<String, Engine> engines;

    @Nullable
    private final HttpServer httpServer;

    /**
     * The AccessManager controls access to the server.
     */
    private final AccessManager accessManager;

    /**
     * The Thrift server controls the RPC protocol. Use
     * https://github.com/m1ch1/mapkeeper/wiki/Thrift-Java-Servers-Compared for
     * a reference.
     */
    private final TServer server;

    /**
     * The server maintains a collection of {@link Transaction} objects to
     * ensure that client requests are properly routed. When the client makes a
     * call to {@link #stage(AccessToken)}, a Transaction is started on the
     * server and a {@link TransactionToken} is used for the client to reference
     * that Transaction in future calls.
     */
    private final Map<TransactionToken, Transaction> transactions = new NonBlockingHashMap<TransactionToken, Transaction>();

    /**
     * Construct a ConcourseServer that listens on {@link #SERVER_PORT} and
     * stores data in {@link Properties#DATA_HOME}.
     * 
     * @throws TTransportException
     */
    public ConcourseServer() throws TTransportException {
        this(CLIENT_PORT, BUFFER_DIRECTORY, DATABASE_DIRECTORY);
    }

    /**
     * Construct a ConcourseServer that listens on {@code port} and store data
     * in {@code dbStore} and {@code bufferStore}.
     * 
     * @param port
     * @param bufferStore
     * @param dbStore
     * @throws TTransportException
     */
    public ConcourseServer(int port, String bufferStore, String dbStore)
            throws TTransportException {
        Preconditions.checkState(!bufferStore.equalsIgnoreCase(dbStore),
                "Cannot store buffer and database files in the same directory. "
                        + "Please check concourse.prefs.");
        Preconditions
                .checkState(!Strings.isNullOrEmpty(Environments
                        .sanitize(DEFAULT_ENVIRONMENT)), "Cannot initialize "
                        + "Concourse Server with a default environment of "
                        + "'%s'. Please use a default environment name that "
                        + "contains only alphanumeric characters.",
                        DEFAULT_ENVIRONMENT);
        FileSystem.mkdirs(bufferStore);
        FileSystem.mkdirs(dbStore);
        FileSystem.lock(bufferStore);
        FileSystem.lock(dbStore);
        TServerSocket socket = new TServerSocket(port);
        ConcourseService.Processor<Iface> processor = new ConcourseService.Processor<Iface>(
                this);
        Args args = new TThreadPoolServer.Args(socket);
        args.processor(processor);
        args.maxWorkerThreads(NUM_WORKER_THREADS);
        args.executorService(Executors
                .newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat(
                        "Client Worker" + " %d").build()));
        this.server = new TThreadPoolServer(args);
        this.bufferStore = bufferStore;
        this.dbStore = dbStore;
        this.engines = Maps.newConcurrentMap();
        this.accessManager = AccessManager.create(ACCESS_FILE);
        this.httpServer = GlobalState.HTTP_PORT > 0 ? HttpServer.create(this,
                GlobalState.HTTP_PORT) : HttpServer.disabled();
        getEngine(); // load the default engine
    }

    @Override
    public void abort(AccessToken creds, TransactionToken transaction,
            String env) throws TException {
        checkAccess(creds, transaction);
        transactions.remove(transaction).abort();
    }

    @Override
    @Atomic
    @AutoRetry
    public long addKeyValue(String key, TObject value, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        long record = 0;
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    record = Time.now();
                    addIfEmptyAtomic(key, value, record, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return record;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public boolean addKeyValueRecord(String key, TObject value, long record,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            if(value.getType() != Type.LINK
                    || isValidLink((Link) Convert.thriftToJava(value), record)) {
                return ((BufferedStore) getStore(transaction, environment))
                        .add(key, value, record);
            }
            else {
                return false;
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<Long, Boolean> addKeyValueRecords(String key, TObject value,
            List<Long> records, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Boolean> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        result.put(record, atomic.add(key, value, record));
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @VersionControl
    public Map<Long, String> auditKeyRecord(String key, long record,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, environment).audit(key, record);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    @VersionControl
    public Map<Long, String> auditKeyRecordStart(String key, long record,
            long start, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        return auditKeyRecordStartEnd(key, record, start, Time.NONE, creds,
                transaction, environment);
    }

    @Override
    @VersionControl
    public Map<Long, String> auditKeyRecordStartEnd(String key, long record,
            long start, long end, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, String> result = Maps.newLinkedHashMap();
            Map<Long, String> base = store.audit(key, record);
            int index = Timestamps.findNearestSuccessorForTimestamp(
                    base.keySet(), start);
            Entry<Long, String> entry = null;
            for (int i = index; i < base.size(); ++i) {
                entry = Iterables.get(base.entrySet(), i);
                if(entry.getKey() >= end) {
                    break;
                }
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, String> auditKeyRecordStartstr(String key, long record,
            String start, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return auditKeyRecordStart(key, record,
                NaturalLanguage.parseMicros(start), creds, transaction,
                environment);
    }

    @Override
    @Alias
    public Map<Long, String> auditKeyRecordStartstrEndstr(String key,
            long record, String start, String end, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return auditKeyRecordStartEnd(key, record,
                NaturalLanguage.parseMicros(start),
                NaturalLanguage.parseMicros(end), creds, transaction,
                environment);
    }

    @Override
    @VersionControl
    public Map<Long, String> auditRecord(long record, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        try {
            return getStore(transaction, environment).audit(record);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    @VersionControl
    public Map<Long, String> auditRecordStart(long record, long start,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        return auditRecordStartEnd(record, start, Time.NONE, creds,
                transaction, environment);
    }

    @Override
    @VersionControl
    public Map<Long, String> auditRecordStartEnd(long record, long start,
            long end, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, String> result = Maps.newLinkedHashMap();
            Map<Long, String> base = store.audit(record);
            int index = Timestamps.findNearestSuccessorForTimestamp(
                    base.keySet(), start);
            Entry<Long, String> entry = null;
            for (int i = index; i < base.size(); ++i) {
                entry = Iterables.get(base.entrySet(), i);
                if(entry.getKey() >= end) {
                    break;
                }
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, String> auditRecordStartstr(long record, String start,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        return auditRecordStart(record, NaturalLanguage.parseMicros(start),
                creds, transaction, environment);
    }

    @Override
    @Alias
    public Map<Long, String> auditRecordStartstrEndstr(long record,
            String start, String end, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return auditRecordStartEnd(record, NaturalLanguage.parseMicros(start),
                NaturalLanguage.parseMicros(end), creds, transaction,
                environment);
    }

    @Override
    public Map<TObject, Set<Long>> browseKey(String key, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, environment).browse(key);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<String, Map<TObject, Set<Long>>> browseKeys(List<String> keys,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<String, Map<TObject, Set<Long>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (String key : keys) {
                        result.put(key, atomic.browse(key));
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Batch
    @HistoricalRead
    public Map<String, Map<TObject, Set<Long>>> browseKeysTime(
            List<String> keys, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<String, Map<TObject, Set<Long>>> result = Maps
                    .newLinkedHashMap();
            for (String key : keys) {
                result.put(key, store.browse(key));
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<String, Map<TObject, Set<Long>>> browseKeysTimestr(
            List<String> keys, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return browseKeysTime(keys, NaturalLanguage.parseMicros(timestamp),
                creds, transaction, environment);
    }

    @Override
    @HistoricalRead
    public Map<TObject, Set<Long>> browseKeyTime(String key, long timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, environment).browse(key, timestamp);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<TObject, Set<Long>> browseKeyTimestr(String key,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return browseKeyTime(key, NaturalLanguage.parseMicros(timestamp),
                creds, transaction, environment);
    }

    @Override
    @Atomic
    @AutoRetry
    public Map<Long, Set<TObject>> chronologizeKeyRecord(String key,
            long record, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Set<TObject>> result = Maps.newLinkedHashMap();
            Map<Long, String> history = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    chronologizeAtomic(key, record, result, history, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    @AutoRetry
    public Map<Long, Set<TObject>> chronologizeKeyRecordStart(String key,
            long record, long start, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return chronologizeKeyRecordStartEnd(key, record, start, Time.NONE,
                creds, transaction, environment);
    }

    @Override
    @AutoRetry
    public Map<Long, Set<TObject>> chronologizeKeyRecordStartEnd(String key,
            long record, long start, long end, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        // TODO review this implementation
        try {
            Map<Long, Set<TObject>> result = Maps.newLinkedHashMap();
            Map<Long, Set<TObject>> base = chronologizeKeyRecord(key, record,
                    creds, transaction, environment);
            int index = Timestamps.findNearestSuccessorForTimestamp(
                    base.keySet(), start);
            Entry<Long, Set<TObject>> entry = null;
            for (int i = index; i < base.size(); ++i) {
                entry = Iterables.get(base.entrySet(), i);
                if(entry.getKey() >= end) {
                    break;
                }
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Set<TObject>> chronologizeKeyRecordStartstr(String key,
            long record, String start, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return chronologizeKeyRecordStart(key, record,
                NaturalLanguage.parseMicros(start), creds, transaction,
                environment);
    }

    @Override
    @Alias
    public Map<Long, Set<TObject>> chronologizeKeyRecordStartstrEndstr(
            String key, long record, String start, String end,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        return chronologizeKeyRecordStartEnd(key, record,
                NaturalLanguage.parseMicros(start),
                NaturalLanguage.parseMicros(end), creds, transaction,
                environment);
    }

    @Override
    @Atomic
    @AutoRetry
    public void clearKeyRecord(String key, long record, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    clearKeyRecordAtomic(key, record, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @AutoRetry
    @Atomic
    @Batch
    public void clearKeyRecords(String key, List<Long> records,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        clearKeyRecordAtomic(key, record, atomic);
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @AutoRetry
    @Atomic
    @Batch
    public void clearKeysRecord(List<String> keys, long record,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (String key : keys) {
                        clearKeyRecordAtomic(key, record, atomic);
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @AutoRetry
    @Atomic
    @Batch
    public void clearKeysRecords(List<String> keys, List<Long> records,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        for (String key : keys) {
                            clearKeyRecordAtomic(key, record, atomic);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @AutoRetry
    public void clearRecord(long record, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    clearRecordAtomic(record, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @AutoRetry
    @Atomic
    @Batch
    public void clearRecords(List<Long> records, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        clearRecordAtomic(record, atomic);
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public boolean commit(AccessToken creds, TransactionToken transaction,
            String env) throws TException {
        checkAccess(creds, transaction);
        try {
            return transactions.remove(transaction).commit();
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public Set<String> describeRecord(long record, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, environment).describe(record);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<Long, Set<String>> describeRecords(List<Long> records,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Set<String>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        result.put(record, atomic.describe(record));
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Batch
    @HistoricalRead
    public Map<Long, Set<String>> describeRecordsTime(List<Long> records,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Set<String>> result = Maps.newLinkedHashMap();
            for (long record : records) {
                result.put(record, store.describe(record, timestamp));
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Set<String>> describeRecordsTimestr(List<Long> records,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return describeRecordsTime(records,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @HistoricalRead
    public Set<String> describeRecordTime(long record, long timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        try {
            return getStore(transaction, environment).describe(record,
                    timestamp);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Set<String> describeRecordTimestr(long record, String timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        return describeRecordTime(record,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public Map<Diff, Set<TObject>> diffKeyRecordStart(String key, long record,
            long start, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        return diffKeyRecordStartEnd(key, record, start, Timestamp.now()
                .getMicros(), creds, transaction, environment);
    }

    @Override
    public Map<Diff, Set<TObject>> diffKeyRecordStartEnd(String key,
            long record, long start, long end, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            Set<TObject> startValues = null;
            Set<TObject> endValues = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    startValues = store.select(key, record, start);
                    endValues = store.select(key, record, end);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            Map<Diff, Set<TObject>> result = Maps.newHashMapWithExpectedSize(2);
            Set<TObject> xor = Sets.symmetricDifference(startValues, endValues);
            int expectedSize = xor.size() / 2;
            Set<TObject> added = Sets.newHashSetWithExpectedSize(expectedSize);
            Set<TObject> removed = Sets
                    .newHashSetWithExpectedSize(expectedSize);
            for (TObject current : xor) {
                if(!startValues.contains(current))
                    added.add(current);
                else {
                    removed.add(current);
                }
            }
            if(!added.isEmpty()) {
                result.put(Diff.ADDED, added);
            }
            if(!removed.isEmpty()) {
                result.put(Diff.REMOVED, removed);
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Diff, Set<TObject>> diffKeyRecordStartstr(String key,
            long record, String start, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return diffKeyRecordStart(key, record,
                NaturalLanguage.parseMicros(start), creds, transaction,
                environment);
    }

    @Override
    @Alias
    public Map<Diff, Set<TObject>> diffKeyRecordStartstrEndstr(String key,
            long record, String start, String end, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return diffKeyRecordStartEnd(key, record,
                NaturalLanguage.parseMicros(start),
                NaturalLanguage.parseMicros(end), creds, transaction,
                environment);
    }

    @Override
    public Map<TObject, Map<Diff, Set<Long>>> diffKeyStart(String key,
            long start, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return diffKeyStartEnd(key, start, Timestamp.now().getMicros(), creds,
                transaction, environment);
    }

    @Override
    public Map<TObject, Map<Diff, Set<Long>>> diffKeyStartEnd(String key,
            long start, long end, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<TObject, Map<Diff, Set<Long>>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            Map<TObject, Set<Long>> startData = null;
            Map<TObject, Set<Long>> endData = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    startData = store.browse(key, start);
                    endData = store.browse(key, end);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            Set<TObject> startValues = startData.keySet();
            Set<TObject> endValues = endData.keySet();
            Set<TObject> xor = Sets.symmetricDifference(startValues, endValues);
            Set<TObject> intersection = startValues.size() < endValues.size() ? Sets
                    .intersection(startValues, endValues) : Sets.intersection(
                    endValues, startValues);
            for (TObject value : xor) {
                Map<Diff, Set<Long>> entry = Maps.newHashMapWithExpectedSize(1);
                if(!startValues.contains(value)) {
                    entry.put(Diff.ADDED, endData.get(value));
                }
                else {
                    entry.put(Diff.REMOVED, endData.get(value));
                }
                result.put(value, entry);
            }
            for (TObject value : intersection) {
                Set<Long> startRecords = startData.get(value);
                Set<Long> endRecords = endData.get(value);
                Set<Long> xorRecords = Sets.symmetricDifference(startRecords,
                        endRecords);
                if(!xorRecords.isEmpty()) {
                    Set<Long> added = Sets
                            .newHashSetWithExpectedSize(xorRecords.size());
                    Set<Long> removed = Sets
                            .newHashSetWithExpectedSize(xorRecords.size());
                    for (Long record : xorRecords) {
                        if(!startRecords.contains(record)) {
                            added.add(record);
                        }
                        else {
                            removed.add(record);
                        }
                    }
                    Map<Diff, Set<Long>> entry = Maps
                            .newHashMapWithExpectedSize(2);
                    if(!added.isEmpty()) {
                        entry.put(Diff.ADDED, added);
                    }
                    if(!removed.isEmpty()) {
                        entry.put(Diff.REMOVED, removed);
                    }
                    result.put(value, entry);
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<TObject, Map<Diff, Set<Long>>> diffKeyStartstr(String key,
            String start, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        return diffKeyStart(key, NaturalLanguage.parseMicros(start), creds,
                transaction, environment);
    }

    @Override
    @Alias
    public Map<TObject, Map<Diff, Set<Long>>> diffKeyStartstrEndstr(String key,
            String start, String end, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return diffKeyStartEnd(key, NaturalLanguage.parseMicros(start),
                NaturalLanguage.parseMicros(end), creds, transaction,
                environment);
    }

    @Override
    public Map<String, Map<Diff, Set<TObject>>> diffRecordStart(long record,
            long start, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return diffRecordStartEnd(record, start, Timestamp.now().getMicros(),
                creds, transaction, environment);
    }

    @Override
    public Map<String, Map<Diff, Set<TObject>>> diffRecordStartEnd(long record,
            long start, long end, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<String, Map<Diff, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            Map<String, Set<TObject>> startData = null;
            Map<String, Set<TObject>> endData = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    startData = store.select(record, start);
                    endData = store.select(record, end);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            Set<String> startKeys = startData.keySet();
            Set<String> endKeys = endData.keySet();
            Set<String> xor = Sets.symmetricDifference(startKeys, endKeys);
            Set<String> intersection = Sets.intersection(startKeys, endKeys);
            for (String key : xor) {
                Map<Diff, Set<TObject>> entry = Maps
                        .newHashMapWithExpectedSize(1);
                if(!startKeys.contains(key)) {
                    entry.put(Diff.ADDED, endData.get(key));
                }
                else {
                    entry.put(Diff.REMOVED, endData.get(key));
                }
                result.put(key, entry);
            }
            for (String key : intersection) {
                Set<TObject> startValues = startData.get(key);
                Set<TObject> endValues = endData.get(key);
                Set<TObject> xorValues = Sets.symmetricDifference(startValues,
                        endValues);
                if(!xorValues.isEmpty()) {
                    Set<TObject> added = Sets
                            .newHashSetWithExpectedSize(xorValues.size());
                    Set<TObject> removed = Sets
                            .newHashSetWithExpectedSize(xorValues.size());
                    for (TObject value : xorValues) {
                        if(!startValues.contains(value)) {
                            added.add(value);
                        }
                        else {
                            removed.add(value);
                        }
                    }
                    Map<Diff, Set<TObject>> entry = Maps
                            .newHashMapWithExpectedSize(2);
                    if(!added.isEmpty()) {
                        entry.put(Diff.ADDED, added);
                    }
                    if(!removed.isEmpty()) {
                        entry.put(Diff.REMOVED, removed);
                    }
                    result.put(key, entry);
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<String, Map<Diff, Set<TObject>>> diffRecordStartstr(long record,
            String start, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return diffRecordStart(record, NaturalLanguage.parseMicros(start),
                creds, transaction, environment);
    }

    @Override
    @Alias
    public Map<String, Map<Diff, Set<TObject>>> diffRecordStartstrEndstr(
            long record, String start, String end, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return diffRecordStartEnd(record, NaturalLanguage.parseMicros(start),
                NaturalLanguage.parseMicros(end), creds, transaction,
                environment);
    }

    @ManagedOperation
    @Override
    @Deprecated
    public String dump(String id) {
        return dump(DEFAULT_ENVIRONMENT);
    }

    @Override
    public String dump(String id, String env) {
        return getEngine(env).dump(id);
    }

    @Override
    public Set<Long> findCcl(String ccl, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    findAtomic(queue, stack, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return Sets.newTreeSet(stack.pop());
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    @Atomic
    @Batch
    public Set<Long> findCriteria(TCriteria criteria, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    findAtomic(queue, stack, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return Sets.newTreeSet(stack.pop());
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Set<Long> findKeyOperatorstrValues(String key, String operator,
            List<TObject> values, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return findKeyOperatorValues(key, Convert.stringToOperator(operator),
                values, creds, transaction, environment);
    }

    @Override
    @Alias
    public Set<Long> findKeyOperatorstrValuesTime(String key, String operator,
            List<TObject> values, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return findKeyOperatorValuesTime(key,
                Convert.stringToOperator(operator), values, timestamp, creds,
                transaction, environment);
    }

    @Override
    @Alias
    public Set<Long> findKeyOperatorstrValuesTimestr(String key,
            String operator, List<TObject> values, String timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        return findKeyOperatorstrValuesTime(key, operator, values,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public Set<Long> findKeyOperatorValues(String key, Operator operator,
            List<TObject> values, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            TObject[] tValues = values.toArray(new TObject[values.size()]);
            return getStore(transaction, environment).find(key, operator,
                    tValues);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @HistoricalRead
    public Set<Long> findKeyOperatorValuesTime(String key, Operator operator,
            List<TObject> values, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            TObject[] tValues = values.toArray(new TObject[values.size()]);
            return getStore(transaction, environment).find(timestamp, key,
                    operator, tValues);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Set<Long> findKeyOperatorValuesTimestr(String key,
            Operator operator, List<TObject> values, String timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        return findKeyOperatorValuesTime(key, operator, values,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @AutoRetry
    @Atomic
    public long findOrAddKeyValue(String key, TObject value, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            Set<Long> records = Sets.newLinkedHashSetWithExpectedSize(1);
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    records.addAll(atomic.find(key, Operator.EQUALS, value));
                    if(records.isEmpty()) {
                        long record = Time.now();
                        addIfEmptyAtomic(key, value, record, atomic);
                        records.add(record);
                    }
                }
                catch (AtomicStateException e) {
                    records.clear();
                    atomic = null;
                }
            }
            if(records.size() == 1) {
                return Iterables.getOnlyElement(records);
            }
            else {
                throw new DuplicateEntryException(
                        org.cinchapi.concourse.util.Strings.joinWithSpace(
                                "Found", records.size(), "records that match",
                                key, "=", value));
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    @Atomic
    public long findOrInsertCclJson(String ccl, String json, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            List<Multimap<String, Object>> objects = Lists.newArrayList(Convert
                    .jsonToJava(json));
            Compoundable store = getStore(transaction, environment);
            Set<Long> records = Sets.newLinkedHashSet();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Queue<PostfixNotationSymbol> queue = Parser
                            .toPostfixNotation(ccl);
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findOrInsertAtomic(records, objects, queue, stack, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                    records.clear();
                }
            }
            if(records.size() == 1) {
                return Iterables.getOnlyElement(records);
            }
            else {
                throw new DuplicateEntryException(
                        org.cinchapi.concourse.util.Strings.joinWithSpace(
                                "Found", records.size(), "records that match",
                                ccl));
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    @Atomic
    public long findOrInsertCriteriaJson(TCriteria criteria, String json,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            List<Multimap<String, Object>> objects = Lists.newArrayList(Convert
                    .jsonToJava(json));
            Compoundable store = getStore(transaction, environment);
            Set<Long> records = Sets.newLinkedHashSet();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findOrInsertAtomic(records, objects, queue, stack, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                    records.clear();
                }
            }
            if(records.size() == 1) {
                return Iterables.getOnlyElement(records);
            }
            else {
                throw new DuplicateEntryException(
                        org.cinchapi.concourse.util.Strings.joinWithSpace(
                                "Found", records.size(), "records that match",
                                Language.translateFromThriftCriteria(criteria)));
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public Map<Long, Map<String, TObject>> getCcl(String ccl,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, TObject> entry = Maps.newHashMap();
                        for (String key : atomic.describe(record)) {
                            try {
                                entry.put(key, Iterables.getLast(atomic.select(
                                        key, record)));
                            }
                            catch (NoSuchElementException e) {
                                continue;
                            }
                        }
                        if(!entry.isEmpty()) {
                            result.put(record, entry);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    public Map<Long, Map<String, TObject>> getCclTime(String ccl,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, TObject> entry = Maps.newHashMap();
                        for (String key : atomic.describe(record, timestamp)) {
                            try {
                                entry.put(key, Iterables.getLast(atomic.select(
                                        key, record, timestamp)));
                            }
                            catch (NoSuchElementException e) {
                                continue;
                            }
                        }
                        if(!entry.isEmpty()) {
                            result.put(record, entry);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, TObject>> getCclTimestr(String ccl,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return getCclTime(ccl, NaturalLanguage.parseMicros(timestamp), creds,
                transaction, environment);
    }

    @Override
    public Map<Long, Map<String, TObject>> getCriteria(TCriteria criteria,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, TObject> entry = Maps.newHashMap();
                        for (String key : atomic.describe(record)) {
                            try {
                                entry.put(key, Iterables.getLast(atomic.select(
                                        key, record)));
                            }
                            catch (NoSuchElementException e) {
                                continue;
                            }
                        }
                        if(!entry.isEmpty()) {
                            result.put(record, entry);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public Map<Long, Map<String, TObject>> getCriteriaTime(TCriteria criteria,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, TObject> entry = Maps.newHashMap();
                        for (String key : atomic.describe(record, timestamp)) {
                            try {
                                entry.put(key, Iterables.getLast(atomic.select(
                                        key, record)));
                            }
                            catch (NoSuchElementException e) {
                                continue;
                            }
                        }
                        if(!entry.isEmpty()) {
                            result.put(record, entry);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, TObject>> getCriteriaTimestr(
            TCriteria criteria, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return getCriteriaTime(criteria,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @ManagedOperation
    @Deprecated
    public String getDumpList() {
        return getDumpList(DEFAULT_ENVIRONMENT);
    }

    @Override
    public String getDumpList(String env) {
        return getEngine(env).getDumpList();
    }

    @Override
    public Map<Long, TObject> getKeyCcl(String key, String ccl,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, TObject> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        try {
                            result.put(record, Iterables.getLast(atomic.select(
                                    key, record)));
                        }
                        catch (NoSuchElementException e) {
                            continue;
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    public Map<Long, TObject> getKeyCclTime(String key, String ccl,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, TObject> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        try {
                            result.put(record, Iterables.getLast(atomic.select(
                                    key, record, timestamp)));
                        }
                        catch (NoSuchElementException e) {
                            continue;
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    @Alias
    public Map<Long, TObject> getKeyCclTimestr(String key, String ccl,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            ParseException, TException {
        return getKeyCclTime(key, ccl, NaturalLanguage.parseMicros(timestamp),
                creds, transaction, environment);
    }

    @Override
    public Map<Long, TObject> getKeyCriteria(String key, TCriteria criteria,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, TObject> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        try {
                            result.put(record, Iterables.getLast(atomic.select(
                                    key, record)));
                        }
                        catch (NoSuchElementException e) {
                            continue;
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public Map<Long, TObject> getKeyCriteriaTime(String key,
            TCriteria criteria, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, TObject> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        try {
                            result.put(record, Iterables.getLast(atomic.select(
                                    key, record, timestamp)));
                        }
                        catch (NoSuchElementException e) {
                            continue;
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, TObject> getKeyCriteriaTimestr(String key,
            TCriteria criteria, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return getKeyCriteriaTime(key, criteria,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public TObject getKeyRecord(String key, long record, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            return Iterables.getLast(
                    getStore(transaction, environment).select(key, record),
                    TObject.NULL);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<Long, TObject> getKeyRecords(String key, List<Long> records,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, TObject> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        try {
                            result.put(record, Iterables.getLast(atomic.select(
                                    key, record)));
                        }
                        catch (NoSuchElementException e) {
                            continue;
                        }
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Batch
    @HistoricalRead
    public Map<Long, TObject> getKeyRecordsTime(String key, List<Long> records,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        checkAccess(creds, transaction);
        try {
            Map<Long, TObject> result = Maps.newLinkedHashMap();
            Compoundable store = getStore(transaction, environment);
            for (long record : records) {
                try {
                    result.put(record, Iterables.getLast(store.select(key,
                            record, timestamp)));
                }
                catch (NoSuchElementException e) {
                    continue;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, TObject> getKeyRecordsTimestr(String key,
            List<Long> records, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return getKeyRecordsTime(key, records,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @HistoricalRead
    public TObject getKeyRecordTime(String key, long record, long timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            return Iterables.getLast(
                    getStore(transaction, environment).select(key, record,
                            timestamp), TObject.NULL);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public TObject getKeyRecordTimestr(String key, long record,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return getKeyRecordTime(key, record,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public Map<Long, Map<String, TObject>> getKeysCcl(List<String> keys,
            String ccl, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, TObject> entry = Maps.newHashMap();
                        for (String key : keys) {
                            try {
                                entry.put(key, Iterables.getLast(atomic.select(
                                        key, record)));
                            }
                            catch (NoSuchElementException e) {
                                continue;
                            }
                        }
                        if(!entry.isEmpty()) {
                            result.put(record, entry);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    public Map<Long, Map<String, TObject>> getKeysCclTime(List<String> keys,
            String ccl, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, TObject> entry = Maps.newHashMap();
                        for (String key : keys) {
                            try {
                                entry.put(key, Iterables.getLast(atomic.select(
                                        key, record, timestamp)));
                            }
                            catch (NoSuchElementException e) {
                                continue;
                            }
                        }
                        if(!entry.isEmpty()) {
                            result.put(record, entry);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, TObject>> getKeysCclTimestr(List<String> keys,
            String ccl, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return getKeysCclTime(keys, ccl,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public Map<Long, Map<String, TObject>> getKeysCriteria(List<String> keys,
            TCriteria criteria, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, TObject> entry = Maps.newHashMap();
                        for (String key : keys) {
                            try {
                                entry.put(key, Iterables.getLast(atomic.select(
                                        key, record)));
                            }
                            catch (NoSuchElementException e) {
                                continue;
                            }
                        }
                        if(!entry.isEmpty()) {
                            result.put(record, entry);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public Map<Long, Map<String, TObject>> getKeysCriteriaTime(
            List<String> keys, TCriteria criteria, long timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, TObject> entry = Maps.newHashMap();
                        for (String key : keys) {
                            try {
                                entry.put(key, Iterables.getLast(atomic.select(
                                        key, record, timestamp)));
                            }
                            catch (NoSuchElementException e) {
                                continue;
                            }
                        }
                        if(!entry.isEmpty()) {
                            result.put(record, entry);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, TObject>> getKeysCriteriaTimestr(
            List<String> keys, TCriteria criteria, String timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        return getKeysCriteriaTime(keys, criteria,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @Atomic
    @Batch
    public Map<String, TObject> getKeysRecord(List<String> keys, long record,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<String, TObject> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (String key : keys) {
                        try {
                            result.put(key, Iterables.getLast(atomic.select(
                                    key, record)));
                        }
                        catch (NoSuchElementException e) {
                            continue;
                        }
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<Long, Map<String, TObject>> getKeysRecords(List<String> keys,
            List<Long> records, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        Map<String, TObject> entry = Maps.newHashMap();
                        for (String key : keys) {
                            try {
                                entry.put(key, Iterables.getLast(atomic.select(
                                        key, record)));
                            }
                            catch (NoSuchElementException e) {
                                continue;
                            }
                        }
                        if(!entry.isEmpty()) {
                            result.put(record, entry);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Batch
    @HistoricalRead
    public Map<Long, Map<String, TObject>> getKeysRecordsTime(
            List<String> keys, List<Long> records, long timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Map<Long, Map<String, TObject>> result = Maps.newLinkedHashMap();
            Compoundable store = getStore(transaction, environment);
            for (long record : records) {
                Map<String, TObject> entry = Maps.newLinkedHashMap();
                for (String key : keys) {
                    try {
                        entry.put(key, Iterables.getLast(store.select(key,
                                record, timestamp)));
                    }
                    catch (NoSuchElementException e) {
                        continue;
                    }
                }
                if(!entry.isEmpty()) {
                    result.put(record, entry);
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, TObject>> getKeysRecordsTimestr(
            List<String> keys, List<Long> records, String timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        return getKeysRecordsTime(keys, records,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @Batch
    @HistoricalRead
    public Map<String, TObject> getKeysRecordTime(List<String> keys,
            long record, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Map<String, TObject> result = Maps.newLinkedHashMap();
            Compoundable store = getStore(transaction, environment);
            for (String key : keys) {
                try {
                    result.put(key, Iterables.getLast(store.select(key, record,
                            timestamp)));
                }
                catch (NoSuchElementException e) {
                    continue;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<String, TObject> getKeysRecordTimestr(List<String> keys,
            long record, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return getKeysRecordTime(keys, record,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public String getServerEnvironment(AccessToken creds,
            TransactionToken transaction, String env) throws SecurityException,
            TException {
        checkAccess(creds, transaction);
        return Environments.sanitize(env);
    }

    @Override
    @ManagedOperation
    public String getServerVersion() {
        return Version.getVersion(ConcourseServer.class).toString();
    }

    @Override
    public Storage getStorage() {
        return getEngine();
    }

    @Override
    public Storage getStorage(String environment) {
        return getEngine(environment);
    }

    @Override
    @ManagedOperation
    public void grant(byte[] username, byte[] password) {
        accessManager.createUser(ByteBuffer.wrap(username),
                ByteBuffer.wrap(password));
        username = null;
        password = null;
    }

    @Override
    @ManagedOperation
    public boolean hasUser(byte[] username) {
        return accessManager.isExistingUsername(ByteBuffer.wrap(username));
    }

    @Override
    @Atomic
    @Batch
    public Set<Long> insertJson(String json, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            List<Multimap<String, Object>> objects = Convert
                    .anyJsonToJava(json);
            Compoundable store = getStore(transaction, environment);
            Set<Long> records = Sets.newLinkedHashSet();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    List<DeferredWrite> deferred = Lists.newArrayList();
                    for (Multimap<String, Object> object : objects) {
                        long record = Time.now();
                        atomic.touch(record);
                        if(insertAtomic(object, record, atomic, deferred)) {
                            records.add(record);
                        }
                        else {
                            throw AtomicStateException.RETRY;
                        }
                    }
                    insertDeferredAtomic(deferred, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                    records.clear();
                }
            }
            return records;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    public boolean insertJsonRecord(String json, long record,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        Compoundable store = getStore(transaction, environment);
        try {
            Multimap<String, Object> data = Convert.jsonToJava(json);
            AtomicOperation atomic = store.startAtomicOperation();
            List<DeferredWrite> deferred = Lists.newArrayList();
            return insertAtomic(data, record, atomic, deferred)
                    && insertDeferredAtomic(deferred, atomic)
                    && atomic.commit();
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (AtomicStateException e) {
            return false;
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<Long, Boolean> insertJsonRecords(String json,
            List<Long> records, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        Compoundable store = getStore(transaction, environment);
        try {
            Multimap<String, Object> data = Convert.jsonToJava(json);
            Map<Long, Boolean> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    List<DeferredWrite> deferred = Lists.newArrayList();
                    for (long record : records) {
                        result.put(record,
                                insertAtomic(data, record, atomic, deferred));
                    }
                    insertDeferredAtomic(deferred, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public Set<Long> inventory(AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            return getEngine(environment).browse();
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @AutoRetry
    public String jsonifyRecords(List<Long> records, boolean identifier,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            String json = "";
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    json = doJsonify(records, 0L, identifier, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return json;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @HistoricalRead
    public String jsonifyRecordsTime(List<Long> records, long timestamp,
            boolean identifier, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            return doJsonify(records, timestamp, identifier,
                    getStore(transaction, environment));
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public String jsonifyRecordsTimestr(List<Long> records, String timestamp,
            boolean identifier, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return jsonifyRecordsTime(records,
                NaturalLanguage.parseMicros(timestamp), identifier, creds,
                transaction, environment);
    }

    @Override
    public String listAllEnvironments() {
        return TCollections.toOrderedListString(TSets.intersection(
                FileSystem.getSubDirs(bufferStore),
                FileSystem.getSubDirs(dbStore)));
    }

    @Override
    public String listAllUserSessions() {
        return TCollections.toOrderedListString(accessManager
                .describeAllAccessTokens());
    }

    @Override
    @ManagedOperation
    public boolean login(byte[] username, byte[] password) {
        try {
            AccessToken token = login(ByteBuffer.wrap(username),
                    ByteBuffer.wrap(password));
            username = null;
            password = null;
            if(token != null) {
                logout(token, null); // NOTE: managed operations don't actually
                                     // need an access token, so we expire it
                                     // immediately
                return true;
            }
            else {
                return false;
            }
        }
        catch (SecurityException e) {
            return false;
        }
        catch (TException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public AccessToken login(ByteBuffer username, ByteBuffer password,
            String env) throws TException {
        validate(username, password);
        getEngine(env);
        return accessManager.getNewAccessToken(username);
    }

    @Override
    public void logout(AccessToken creds, String env) throws TException {
        checkAccess(creds, null);
        accessManager.expireAccessToken(creds);
    }

    @Override
    public boolean pingRecord(long record, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            return doPing(record, getStore(transaction, environment));
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<Long, Boolean> pingRecords(List<Long> records,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Boolean> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        result.put(record, doPing(record, atomic));
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public boolean removeKeyValueRecord(String key, TObject value, long record,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            if(value.getType() != Type.LINK
                    || isValidLink((Link) Convert.thriftToJava(value), record)) {
                return ((BufferedStore) getStore(transaction, environment))
                        .remove(key, value, record);
            }
            else {
                return false;
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<Long, Boolean> removeKeyValueRecords(String key, TObject value,
            List<Long> records, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Boolean> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        result.put(record, atomic.remove(key, value, record));
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    @VersionControl
    @AutoRetry
    public void revertKeyRecordsTime(String key, List<Long> records,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        revertAtomic(key, record, timestamp, atomic);
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }

    }

    @Override
    @Alias
    public void revertKeyRecordsTimestr(String key, List<Long> records,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        revertKeyRecordsTime(key, records,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @Atomic
    @VersionControl
    @AutoRetry
    public void revertKeyRecordTime(String key, long record, long timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    revertAtomic(key, record, timestamp, atomic);
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public void revertKeyRecordTimestr(String key, long record,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        revertKeyRecordTime(key, record,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @Atomic
    @Batch
    @VersionControl
    @AutoRetry
    public void revertKeysRecordsTime(List<String> keys, List<Long> records,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        for (String key : keys) {
                            revertAtomic(key, record, timestamp, atomic);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public void revertKeysRecordsTimestr(List<String> keys, List<Long> records,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        revertKeysRecordsTime(keys, records,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @Atomic
    @Batch
    @VersionControl
    @AutoRetry
    public void revertKeysRecordTime(List<String> keys, long record,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (String key : keys) {
                        revertAtomic(key, record, timestamp, atomic);
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public void revertKeysRecordTimestr(List<String> keys, long record,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        revertKeysRecordTime(keys, record,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @ManagedOperation
    public void revoke(byte[] username) {
        accessManager.deleteUser(ByteBuffer.wrap(username));
        username = null;
    }

    @Override
    public Set<Long> search(String key, String query, AccessToken creds,
            TransactionToken transaction, String env) throws TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, env).search(key, query);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public Map<Long, Map<String, Set<TObject>>> selectCcl(String ccl,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, Set<TObject>> entry = Maps.newHashMap();
                        for (String key : atomic.describe(record)) {
                            entry.put(key, atomic.select(key, record));
                        }
                        result.put(record, entry);
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    public Map<Long, Map<String, Set<TObject>>> selectCclTime(String ccl,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, Set<TObject>> entry = Maps.newHashMap();
                        for (String key : atomic.describe(record, timestamp)) {
                            entry.put(key,
                                    atomic.select(key, record, timestamp));
                        }
                        result.put(record, entry);
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, Set<TObject>>> selectCclTimestr(String ccl,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return selectCclTime(ccl, NaturalLanguage.parseMicros(timestamp),
                creds, transaction, environment);
    }

    @Override
    public Map<Long, Map<String, Set<TObject>>> selectCriteria(
            TCriteria criteria, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, Set<TObject>> entry = Maps.newHashMap();
                        for (String key : atomic.describe(record)) {
                            entry.put(key, atomic.select(key, record));
                        }
                        result.put(record, entry);
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public Map<Long, Map<String, Set<TObject>>> selectCriteriaTime(
            TCriteria criteria, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, Set<TObject>> entry = Maps.newHashMap();
                        for (String key : atomic.describe(record, timestamp)) {
                            entry.put(key,
                                    atomic.select(key, record, timestamp));
                        }
                        result.put(record, entry);
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, Set<TObject>>> selectCriteriaTimestr(
            TCriteria criteria, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return selectCriteriaTime(criteria,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public Map<Long, Set<TObject>> selectKeyCcl(String key, String ccl,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Set<TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        result.put(record, atomic.select(key, record));
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    public Map<Long, Set<TObject>> selectKeyCclTime(String key, String ccl,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Set<TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        result.put(record,
                                atomic.select(key, record, timestamp));
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    @Alias
    public Map<Long, Set<TObject>> selectKeyCclTimestr(String key, String ccl,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            ParseException, TException {
        return selectKeyCclTime(key, ccl,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public Map<Long, Set<TObject>> selectKeyCriteria(String key,
            TCriteria criteria, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Set<TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        result.put(record, atomic.select(key, record));
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public Map<Long, Set<TObject>> selectKeyCriteriaTime(String key,
            TCriteria criteria, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Set<TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        result.put(record,
                                atomic.select(key, record, timestamp));
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Set<TObject>> selectKeyCriteriaTimestr(String key,
            TCriteria criteria, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return selectKeyCriteriaTime(key, criteria,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public Set<TObject> selectKeyRecord(String key, long record,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, environment).select(key, record);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<Long, Set<TObject>> selectKeyRecords(String key,
            List<Long> records, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Set<TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        result.put(record, atomic.select(key, record));
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    @HistoricalRead
    public Map<Long, Set<TObject>> selectKeyRecordsTime(String key,
            List<Long> records, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Set<TObject>> result = Maps.newLinkedHashMap();
            for (long record : records) {
                result.put(record, store.select(key, record, timestamp));
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Set<TObject>> selectKeyRecordsTimestr(String key,
            List<Long> records, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return selectKeyRecordsTime(key, records,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @HistoricalRead
    public Set<TObject> selectKeyRecordTime(String key, long record,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, environment).select(key, record,
                    timestamp);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Set<TObject> selectKeyRecordTimestr(String key, long record,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return selectKeyRecordTime(key, record,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public Map<Long, Map<String, Set<TObject>>> selectKeysCcl(
            List<String> keys, String ccl, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, Set<TObject>> entry = Maps.newHashMap();
                        for (String key : keys) {
                            entry.put(key, atomic.select(key, record));
                        }
                        result.put(record, entry);
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    public Map<Long, Map<String, Set<TObject>>> selectKeysCclTime(
            List<String> keys, String ccl, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = Parser.toPostfixNotation(ccl);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, Set<TObject>> entry = Maps.newHashMap();
                        for (String key : keys) {
                            entry.put(key,
                                    atomic.select(key, record, timestamp));
                        }
                        result.put(record, entry);
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, Set<TObject>>> selectKeysCclTimestr(
            List<String> keys, String ccl, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return selectKeysCclTime(keys, ccl,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public Map<Long, Map<String, Set<TObject>>> selectKeysCriteria(
            List<String> keys, TCriteria criteria, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, Set<TObject>> entry = Maps.newHashMap();
                        for (String key : keys) {
                            entry.put(key, atomic.select(key, record));
                        }
                        result.put(record, entry);
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public Map<Long, Map<String, Set<TObject>>> selectKeysCriteriaTime(
            List<String> keys, TCriteria criteria, long timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Queue<PostfixNotationSymbol> queue = convertCriteriaToQueue(criteria);
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Deque<Set<Long>> stack = new ArrayDeque<Set<Long>>();
                    findAtomic(queue, stack, atomic);
                    Set<Long> records = stack.pop();
                    for (long record : records) {
                        Map<String, Set<TObject>> entry = Maps.newHashMap();
                        for (String key : keys) {
                            entry.put(key,
                                    atomic.select(key, record, timestamp));
                        }
                        result.put(record, entry);
                    }
                }
                catch (AtomicStateException e) {
                    result.clear();
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, Set<TObject>>> selectKeysCriteriaTimestr(
            List<String> keys, TCriteria criteria, String timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        return selectKeysCriteriaTime(keys, criteria,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @Atomic
    @Batch
    public Map<String, Set<TObject>> selectKeysRecord(List<String> keys,
            long record, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<String, Set<TObject>> result = Maps.newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (String key : keys) {
                        result.put(key, atomic.select(key, record));
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<Long, Map<String, Set<TObject>>> selectKeysRecords(
            List<String> keys, List<Long> records, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        Map<String, Set<TObject>> entry = Maps.newHashMap();
                        for (String key : keys) {
                            entry.put(key, atomic.select(key, record));
                        }
                        if(!entry.isEmpty()) {
                            result.put(record, entry);
                        }
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Batch
    @HistoricalRead
    public Map<Long, Map<String, Set<TObject>>> selectKeysRecordsTime(
            List<String> keys, List<Long> records, long timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            for (long record : records) {
                Map<String, Set<TObject>> entry = Maps.newHashMap();
                for (String key : keys) {
                    entry.put(key, store.select(key, record, timestamp));
                }
                if(!entry.isEmpty()) {
                    result.put(record, entry);
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, Set<TObject>>> selectKeysRecordsTimestr(
            List<String> keys, List<Long> records, String timestamp,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        return selectKeysRecordsTime(keys, records,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @Batch
    @HistoricalRead
    public Map<String, Set<TObject>> selectKeysRecordTime(List<String> keys,
            long record, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<String, Set<TObject>> result = Maps.newLinkedHashMap();
            for (String key : keys) {
                result.put(key, store.select(key, record, timestamp));
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<String, Set<TObject>> selectKeysRecordTimestr(List<String> keys,
            long record, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return selectKeysRecordTime(keys, record,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    public Map<String, Set<TObject>> selectRecord(long record,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, environment).select(record);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Atomic
    @Batch
    public Map<Long, Map<String, Set<TObject>>> selectRecords(
            List<Long> records, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        result.put(record, atomic.select(record));
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Batch
    @HistoricalRead
    public Map<Long, Map<String, Set<TObject>>> selectRecordsTime(
            List<Long> records, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            Map<Long, Map<String, Set<TObject>>> result = Maps
                    .newLinkedHashMap();
            for (long record : records) {
                result.put(record, store.select(record, timestamp));
            }
            return result;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<Long, Map<String, Set<TObject>>> selectRecordsTimestr(
            List<Long> records, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return selectRecordsTime(records,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Override
    @HistoricalRead
    public Map<String, Set<TObject>> selectRecordTime(long record,
            long timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws SecurityException, TransactionException,
            TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, environment).select(record, timestamp);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public Map<String, Set<TObject>> selectRecordTimestr(long record,
            String timestamp, AccessToken creds, TransactionToken transaction,
            String environment) throws TException {
        return selectRecordTime(record, NaturalLanguage.parseMicros(timestamp),
                creds, transaction, environment);
    }

    @Override
    @Alias
    public long setKeyValue(String key, TObject value, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return addKeyValue(key, value, creds, transaction, environment);
    }

    @Override
    public void setKeyValueRecord(String key, TObject value, long record,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            ((BufferedStore) getStore(transaction, environment)).set(key,
                    value, record);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }

    }

    @Override
    @Atomic
    @Batch
    public void setKeyValueRecords(String key, TObject value,
            List<Long> records, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, environment);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    for (long record : records) {
                        atomic.set(key, value, record);
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    public TransactionToken stage(AccessToken creds, String env)
            throws TException {
        checkAccess(creds, null);
        TransactionToken token = new TransactionToken(creds, Time.now());
        Transaction transaction = getEngine(env).startTransaction();
        transactions.put(token, transaction);
        Logger.info("Started Transaction {}", transaction);
        return token;
    }

    /**
     * Start the server.
     * 
     * @throws TTransportException
     */
    public void start() throws TTransportException {
        for (Engine engine : engines.values()) {
            engine.start();
        }
        httpServer.start();
        System.out.println("The Concourse server has started");
        server.serve();
    }

    /**
     * Stop the server.
     */
    public void stop() {
        if(server.isServing()) {
            server.stop();
            httpServer.stop();
            for (Engine engine : engines.values()) {
                engine.stop();
            }
            System.out.println("The Concourse server has stopped");
        }
    }

    @Override
    public long time(AccessToken creds, TransactionToken token,
            String environment) throws TException {
        return Time.now();
    }

    @Override
    public long timePhrase(String phrase, AccessToken creds,
            TransactionToken token, String environment) throws TException {
        try {
            return NaturalLanguage.parseMicros(phrase);
        }
        catch (Exception e) {
            throw new ParseException(e.getMessage());
        }
    }

    @Atomic
    @Override
    public boolean verifyAndSwap(String key, TObject expected, long record,
            TObject replacement, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            AtomicOperation atomic = getStore(transaction, environment)
                    .startAtomicOperation();
            return atomic.remove(key, expected, record)
                    && atomic.add(key, replacement, record) ? atomic.commit()
                    : false;
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
        catch (AtomicStateException e) {
            return false;
        }
    }

    @Override
    public boolean verifyKeyValueRecord(String key, TObject value, long record,
            AccessToken creds, TransactionToken transaction, String environment)
            throws TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, environment)
                    .verify(key, value, record);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @HistoricalRead
    public boolean verifyKeyValueRecordTime(String key, TObject value,
            long record, long timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        checkAccess(creds, transaction);
        try {
            return getStore(transaction, environment).verify(key, value,
                    record, timestamp);
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    @Override
    @Alias
    public boolean verifyKeyValueRecordTimestr(String key, TObject value,
            long record, String timestamp, AccessToken creds,
            TransactionToken transaction, String environment) throws TException {
        return verifyKeyValueRecordTime(key, value, record,
                NaturalLanguage.parseMicros(timestamp), creds, transaction,
                environment);
    }

    @Atomic
    @Override
    @AutoRetry
    public void verifyOrSet(String key, TObject value, long record,
            AccessToken creds, TransactionToken transaction, String env)
            throws TException {
        checkAccess(creds, transaction);
        try {
            Compoundable store = getStore(transaction, env);
            AtomicOperation atomic = null;
            while (atomic == null || !atomic.commit()) {
                atomic = store.startAtomicOperation();
                try {
                    Set<TObject> values = atomic.select(key, record);
                    for (TObject val : values) {
                        if(!val.equals(value)) {
                            atomic.remove(key, val, record);
                        }
                    }
                    if(!atomic.verify(key, value, record)) {
                        atomic.add(key, value, record);
                    }
                }
                catch (AtomicStateException e) {
                    atomic = null;
                }
            }
        }
        catch (TransactionStateException e) {
            throw new TransactionException();
        }
    }

    /**
     * Check to make sure that {@code creds} and {@code transaction} are valid
     * and are associated with one another.
     * 
     * @param creds
     * @param transaction
     * @throws SecurityException
     * @throws IllegalArgumentException
     */
    private void checkAccess(AccessToken creds,
            @Nullable TransactionToken transaction) throws SecurityException,
            IllegalArgumentException {
        if(!accessManager.isValidAccessToken(creds)) {
            throw new SecurityException("Invalid access token");
        }
        Preconditions.checkArgument((transaction != null
                && transaction.getAccessToken().equals(creds) && transactions
                    .containsKey(transaction)) || transaction == null);
    }

    /**
     * Return the {@link Engine} that is associated with the
     * {@link Default#ENVIRONMENT}.
     * 
     * @return the Engine
     */
    private Engine getEngine() {
        return getEngine(DEFAULT_ENVIRONMENT);
    }

    /**
     * Return the {@link Engine} that is associated with {@code env}. If such an
     * Engine does not exist, create a new one and add it to the collection.
     * 
     * @param env
     * @return the Engine
     */
    private Engine getEngine(String env) {
        Engine engine = engines.get(env);
        if(engine == null) {
            env = Environments.sanitize(env);
            return getEngineUnsafe(env);
        }
        return engine;
    }

    /**
     * Return the {@link Engine} that is associated with {@code env} without
     * performing any sanitzation on the name. If such an Engine does not exist,
     * create a new one and add it to the collection.
     */
    private Engine getEngineUnsafe(String env) {
        Engine engine = engines.get(env);
        if(engine == null) {
            engine = new Engine(bufferStore + File.separator + env, dbStore
                    + File.separator + env, env);
            engine.start();
            engines.put(env, engine);
        }
        return engine;
    }

    /**
     * Return the correct store to use for a read/write operation depending upon
     * the {@code env} whether the client has submitted a {@code transaction}
     * token.
     * 
     * @param transaction
     * @param env
     * @return the store to use
     */
    private Compoundable getStore(TransactionToken transaction, String env) {
        return transaction != null ? transactions.get(transaction)
                : getEngine(env);
    }

    /**
     * A version of the login routine that handles the case when no environment
     * has been specified. The is most common when authenticating a user for
     * managed operations.
     * 
     * @param username
     * @param password
     * @return the access token
     * @throws TException
     */
    private AccessToken login(ByteBuffer username, ByteBuffer password)
            throws TException {
        return login(username, password, DEFAULT_ENVIRONMENT);
    }

    /**
     * Validate that the {@code username} and {@code password} pair represent
     * correct credentials. If not, throw a SecurityException.
     * 
     * @param username
     * @param password
     * @throws SecurityException
     */
    private void validate(ByteBuffer username, ByteBuffer password)
            throws SecurityException {
        if(!accessManager.isExistingUsernamePasswordCombo(username, password)) {
            throw new SecurityException(
                    "Invalid username/password combination.");
        }
    }

    /**
     * A {@link DeferredWrite} is a wrapper around a key, value, and record.
     * This is
     * typically used by Concourse Server to gather certain writes during a
     * batch
     * operation that shouldn't be tried until the end.
     * 
     * @author Jeff Nelson
     */
    @Immutable
    public static final class DeferredWrite {

        // NOTE: This class does not define hashCode() or equals() because the
        // defaults are the desired behaviour.

        private final String key;
        private final long record;
        private final Object value;

        /**
         * Construct a new instance.
         * 
         * @param key
         * @param value
         * @param record
         */
        public DeferredWrite(String key, Object value, long record) {
            this.key = key;
            this.value = value;
            this.record = record;
        }

        /**
         * Return the key.
         * 
         * @return the key
         */
        public String getKey() {
            return key;
        }

        /**
         * Return the record.
         * 
         * @return the record
         */
        public long getRecord() {
            return record;
        }

        /**
         * Return the value.
         * 
         * @return the value
         */
        public Object getValue() {
            return value;
        }

    }

}