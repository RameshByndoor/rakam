package org.rakam.report;

import com.facebook.presto.jdbc.PrestoDriver;
import com.facebook.presto.jdbc.internal.client.StatementClient;
import com.facebook.presto.jdbc.internal.client.StatementStats;
import com.facebook.presto.jdbc.internal.guava.base.Throwables;
import com.facebook.presto.jdbc.internal.guava.collect.Lists;
import org.rakam.server.http.RakamHttpRequest;
import org.rakam.util.JsonHelper;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.rakam.util.JsonHelper.encode;

/**
 * Created by buremba <Burak Emre Kabakcı> on 09/03/15 16:10.
 */
public class PrestoQueryExecutor {
    private static final ScheduledExecutorService queryExecutor = Executors.newScheduledThreadPool(16);

    private final RakamHttpRequest.StreamResponse response;
    private final String prestoAddress;

    public PrestoQueryExecutor(RakamHttpRequest.StreamResponse response, String prestoAddress) {
        this.response = response;
        this.prestoAddress = prestoAddress;
    }

    private PrestoExecutor.Stats currentStats(StatementClient client) {
        StatementStats stats = client.current().getStats();
        int totalSplits = stats.getTotalSplits();
        int percentage = totalSplits == 0 ? 0 : stats.getCompletedSplits() * 100 / totalSplits;
        return new PrestoExecutor.Stats(percentage,
                stats.getState(),
                stats.getNodes(),
                stats.getProcessedRows(),
                stats.getProcessedBytes(),
                stats.getUserTimeMillis(),
                stats.getCpuTimeMillis(),
                stats.getWallTimeMillis());

    }

    public void executeQuery(String query) {
        StatementClient client = startQuery(query);
        response.send("stats", encode(currentStats(client)));
        List<List<Object>> results = Lists.newArrayList();
        queryExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                client.advance();
                if (client.isFailed()) {
                    response.send("result", JsonHelper.jsonObject()
                            .put("success", false)
                            .put("query", client.getQuery())
                            .put("message", client.current().getError().getMessage()).toString()).end();
                } else if (!client.isValid()) {
                    Optional.ofNullable(client.finalResults().getData())
                            .ifPresent((newResults) -> newResults.forEach(results::add));

                    List<PrestoExecutor.Column> columns = Lists.newArrayList();
                    List<com.facebook.presto.jdbc.internal.client.Column> internalColumns = client.finalResults().getColumns();
                    for (int i = 0; i < internalColumns.size(); i++) {
                        com.facebook.presto.jdbc.internal.client.Column c = internalColumns.get(i);
                        columns.add(new PrestoExecutor.Column(c.getName(), c.getType(), i+1));
                    }
                    response.send("result", encode(JsonHelper.jsonObject()
                            .put("success", true)
                            .putPOJO("query", client.getQuery())
                            .putPOJO("result", results)
                            .putPOJO("metadata", columns))).end();
                } else {
                    Optional.ofNullable(client.current().getData())
                            .ifPresent((newResults) -> newResults.forEach(results::add));

                    response.send("stats", encode(currentStats(client)));
                    queryExecutor.schedule(this, 500, TimeUnit.MILLISECONDS);
                }

            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    private StatementClient startQuery(String query) {
        PrestoDriver prestoDriver = new PrestoDriver();
        Properties properties = new Properties();
        properties.put("user", "Rakam");

        Connection connect;
        try {
            connect = prestoDriver.connect("jdbc:presto://" + prestoAddress, properties);
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }

        // ugly hack since presto doesn't have a standalone client yet.
        try {
            Class<? extends Connection> aClass = connect.getClass();
            Method startQuery = aClass.getDeclaredMethod("startQuery", String.class);
            startQuery.setAccessible(true);
            return (StatementClient) startQuery.invoke(connect, query);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }
}