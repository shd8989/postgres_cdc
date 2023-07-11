package net.bitnine.graphizer.service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.tomcat.util.json.JSONParser;
import org.h2.util.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.bitnine.graphizer.model.entity.DeleteVertexEntity;
import net.bitnine.graphizer.model.entity.InsertVertexEntity;
import net.bitnine.graphizer.model.entity.UpdateVertexEntity;
import net.bitnine.graphizer.model.entity.MetaEntity;

@Service
public class KafkaConsumerService {
    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;
    // Todo
    // auto commit false로 하고 아래 쿼리들 진행
    // 1. spring.datasource.hikari.auto-commit: false
    // 2. getJdbcTemplate().getDataSource().getConnection().setAutoCommit(false);

    /*
    @Deprecated
    public List<ColumnInfoEntity> labelData(String schemaName, String tableName) {
        String sql = "SELECT li.label_type, li.label_id, mi.meta_id, si.source_id, ci.column_id "
            + "FROM tb_label_info li "
            + "JOIN tb_meta_info mi ON li.meta_id = mi.meta_id "
            + "JOIN tb_source_info si ON mi.meta_id = si.meta_id "
            + "JOIN tb_column_info ci ON si.column_id = ci.column_id "
            + "WHERE ci.schema_name = ? "
            + "AND ci.table_name = ? "
            + "AND si.meta_key_yn = 'Y' "
            + "ORDER BY li.label_id, mi.meta_id, si.source_id, ci.column_id";

        // List<Map<String, Object>> list = jdbcTemplate.query(sql, new Object[] {schemaName, tableName}, String.class, String.class);
        // List<ColumnInfoEntity> clist = new ArrayList<>();
        // list.forEach(m -> {
        //     ColumnInfoEntity columnInfoEntity = new ColumnInfoEntity(
        //         (String)m.get("label_type"),
        //         (long)m.get("label_id"),
        //         (long)m.get("meta_id"),
        //         (long)m.get("source_id"),
        //         (long)m.get("column_id"));
        //         clist.add(columnInfoEntity);
        // });
        // return clist;

        return jdbcTemplate.query(sql, new Object[] {schemaName, tableName}, (rs, rowNum) ->
            new ColumnInfoEntity(
                rs.getString("label_type"),
                rs.getLong("label_id"),
                rs.getLong("meta_id"),
                rs.getLong("source_id"),
                rs.getLong("column_id")
            )
        );
    } */

    @Deprecated
    public void insertData(JsonObject after, String schemaName, String tableName) {
        List<MetaEntity> metaEntityList = selectMetaQuery(schemaName, tableName);
        if(metaEntityList.size() > 0) {
            for(int i=0; i<metaEntityList.size(); i++) {
                String[] metaData = metaEntityList.get(i).getMeta_data().split(",");
                String pkColumn = metaData[2];
                String[] mappedData = metaEntityList.get(i).getMapped_data().split("!");
                String[] mappedDataDetail = {};
                String[] propertyData = metaEntityList.get(i).getProperty_data().split(",");

                String metaColumns = "";
                for(int j=1; j<propertyData.length; j++) {
                    if("".equals(metaColumns)) {
                        metaColumns = propertyData[j];
                    } else {
                        metaColumns = metaColumns + "," + propertyData[j];
                    }
                }
                String columns = "";
                String JOINQuery = "";
                for(int j=3; j<metaData.length; j++) {
                    if("".equals(columns)) {
                        columns = "a." + metaData[j];
                    } else {
                        columns = columns + ", a." + metaData[j];
                    }
                }
                for(int j=0; j<mappedData.length; j++) {
                    mappedDataDetail = mappedData[j].split(",");
                    String alias = " b" + j;
                    JOINQuery = JOINQuery +  " LEFT JOIN " + mappedDataDetail[0] + "." + mappedDataDetail[1] + alias
                        + " ON a." + pkColumn + " =" + alias + "." + mappedDataDetail[2];
                    for(int k=3; k<mappedDataDetail.length; k++) {
                        if("".equals(columns)) {
                            columns = alias + "." + mappedDataDetail[k];
                        } else {
                            columns = columns + ", " + alias + "." + mappedDataDetail[k];
                        }
                    }
                }
                String metaSchema = metaEntityList.get(i).getMeta_schema_name();
                String metaTable = metaEntityList.get(i).getMeta_table_name();
                String insertForMetaSql = "INSERT INTO " + metaSchema + "." + metaTable
                    + " (" + metaColumns + ")"
                    + " SELECT " + columns
                    + " FROM " + metaData[0] + "." + metaData[1] + " a"
                    + JOINQuery
                    + " WHERE a." + metaData[2] + " = " + after.getAsJsonObject().get(metaData[2]).getAsString() + "::text";
                try {
                    jdbcTemplate.execute(insertForMetaSql);

                    InsertVertexEntity insertVertexEntity = insertVertexQuery(metaEntityList.get(i).getMeta_id());
                    if(insertVertexEntity != null) {
                        String graphName = insertVertexEntity.getGraph_name();
                        String labelName = insertVertexEntity.getTarget_label_name();
                        String[] colms = insertVertexEntity.getSource_column_name().split(",");
                        String[] pops = insertVertexEntity.getProperty_name().split(",");

                        String conditions = " AND " + insertVertexEntity.getMeta_pk_column() + " = " + after.getAsJsonObject().get(pkColumn).getAsString() + "::text";

                        String buildmap = "";
                        for(int j=0; j<pops.length; j++) {
                            if(j > 0) {
                                buildmap = buildmap + ", '" + pops[j] + "', a." + colms[j];
                            } else {
                                buildmap = "'" + pops[j] + "', a." + pops[j];
                            }
                        }

                        String insertSql = 
                            "INSERT INTO " + graphName + "." + labelName
                            + " SELECT _graphid((_label_id('" + graphName + "'::name, '" + labelName + "'::name))::integer, nextval('" + graphName + "." + labelName + "_id_seq'::regclass)), "
                            + "     agtype_build_map ("+ buildmap +") "
                            + "FROM "
                            + "( "
                            + "     SELECT " + insertVertexEntity.getProperty_name().toString()
                            + "     FROM  " + metaSchema + "." + metaTable
                            + "     WHERE 1 = 1 " + conditions
                            + ") AS a;";
                        try {
                            jdbcTemplate.execute(insertSql);
                            jdbcTemplate.getDataSource().getConnection().commit();
                        } catch(Exception e) {
                            jdbcTemplate.getDataSource().getConnection().rollback();
                            System.out.println(e.getMessage());
                        }
                    }
                } catch(Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        }
    }

    @Deprecated
    private List<MetaEntity> selectMetaQuery(String schemaName, String tableName) {
        try {
            String sql = " WITH sc AS ("
                + "     SELECT si.meta_id"
                + "     FROM tb_column_info ci"
                + "     JOIN tb_source_info si ON ci.column_id = si.column_id"
                + "     WHERE ci.schema_name = ?"
                + "     AND ci.table_name = ?"
                + "     GROUP BY si.meta_id"
                + " ), tb AS ("
                + " 	SELECT sc.meta_id, ci.schema_name AS meta_key_schema_name, ci.table_name AS meta_key_table_name"
                + " 	FROM tb_meta_info mi"
                + " 	JOIN tb_source_info si ON si.meta_id = mi.meta_id"
                + " 	JOIN tb_column_info ci ON ci.column_id = si.column_id"
                + " 	JOIN sc ON sc.meta_id = mi.meta_id AND sc.meta_id = si.meta_id"
                + " 	WHERE si.meta_key_yn = 'Y'"
                + " 	AND mi.use_yn = 'Y'"
                + " 	GROUP BY sc.meta_id, meta_key_schema_name, meta_key_table_name"
                + " ), mt AS ("
                + " 	SELECT a.meta_id, a.meta_schema_name, a.meta_table_name, a.meta_key_schema_name, a.meta_key_table_name, a.meta_key_schema_name || ',' || a.meta_key_table_name || ',' || a.meta_pk_column_name || ',' || string_agg(a.column_name, ',') AS meta_data, (select pi.property_name from tb_property_info pi join tb_source_info si on pi.source_id = si.source_id join tb_column_info ci on ci.column_id = si.column_id where si.meta_id = a.meta_id and si.meta_key_yn = 'Y') || ',' || string_agg(a.property_name, ',') AS property_data"
                + " 	FROM ("
                + " 	    SELECT mi.meta_id, mi.meta_schema_name, mi.meta_table_name, ci.schema_name AS meta_key_schema_name, ci.table_name AS meta_key_table_name, (SELECT column_name FROM tb_column_info WHERE column_id = si.source_pk_column_id) AS meta_pk_column_name, ci.column_name, pi.property_name"
                + " 	    FROM tb_meta_info mi"
                + " 	    JOIN tb_source_info si ON si.meta_id = mi.meta_id"
                + " 	    JOIN tb_column_info ci ON ci.column_id = si.column_id"
                + " 	    JOIN tb_property_info pi ON pi.source_id = si.source_id"
                + " 	    JOIN sc ON sc.meta_id = mi.meta_id"
                + " 	    JOIN tb ON tb.meta_key_schema_name = ci.schema_name AND tb.meta_key_table_name = ci.table_name"
                + " 	    WHERE mi.use_yn = 'Y'"
                + " 	    GROUP BY mi.meta_id, mi.meta_schema_name, mi.meta_table_name, ci.schema_name, ci.table_name, meta_pk_column_name, ci.column_name, pi.property_name"
                + " 	) a"
                + " 	GROUP BY a.meta_id, a.meta_schema_name, a.meta_table_name, a.meta_key_schema_name, a.meta_key_table_name, a.meta_pk_column_name"
                + " ), mp AS ("
                + " 	SELECT b.meta_id, b.meta_schema_name, b.meta_table_name, string_agg(b.mapped_data, '!') AS mapped_data, CASE WHEN mt.property_data != '' THEN mt.property_data || ',' || string_agg(b.property_name, ',') ELSE string_agg(b.property_name, ',') END AS property_data"
                + " 	FROM ("
                + " 		SELECT a.meta_id, a.meta_schema_name, a.meta_table_name, a.mapped_key_schema_name || ',' || a.mapped_key_table_name || ',' || a.mapped_pk_column_name || ',' || string_agg(a.column_name, ',') AS mapped_data, a.property_name"
                + " 		FROM ("
                + " 		    SELECT mi.meta_id, mi.meta_schema_name, mi.meta_table_name, ci.schema_name AS mapped_key_schema_name, ci.table_name AS mapped_key_table_name, (SELECT column_name FROM tb_column_info WHERE column_id = si.source_pk_column_id) AS mapped_pk_column_name, ci.column_name, pi.property_name"
                + " 		    FROM tb_meta_info mi"
                + " 		    JOIN tb_source_info si ON si.meta_id = mi.meta_id"
                + " 		    JOIN tb_column_info ci ON ci.column_id = si.column_id"
                + " 		    JOIN tb_property_info pi ON pi.source_id = si.source_id"
                + " 		    JOIN sc ON sc.meta_id = mi.meta_id"
                + " 		    JOIN tb ON (tb.meta_key_schema_name <> ci.schema_name or tb.meta_key_table_name <> ci.table_name) AND tb.meta_id = sc.meta_id"
                + " 		    WHERE mi.use_yn = 'Y'"
                + " 		    GROUP BY mi.meta_id, mi.meta_schema_name, mi.meta_table_name, ci.schema_name, ci.table_name, mapped_pk_column_name, ci.column_name, pi.property_name"
                + " 		) a"
                + " 		GROUP BY a.meta_id, a.meta_schema_name, a.meta_table_name, a.mapped_key_schema_name, a.mapped_key_table_name, a.mapped_pk_column_name, a.property_name"
                + " 	) b"
                + " 	JOIN mt ON mt.meta_id = b.meta_id"
                + " 	GROUP BY b.meta_id, b.meta_schema_name, b.meta_table_name, mt.property_data"
                + " )"
                + " SELECT mt.meta_id, mt.meta_schema_name, mt.meta_table_name, mt.meta_data, mp.mapped_data, mp.property_data"
                + " FROM mt"
                + " JOIN mp ON mt.meta_id = mp.meta_id";
            
            return jdbcTemplate.query(sql, new Object[] {schemaName, tableName}, (rs, rowNum) ->
                new MetaEntity(
                    rs.getLong("meta_id"),
                    rs.getString("meta_schema_name"),
                    rs.getString("meta_table_name"),
                    rs.getString("meta_data"),
                    rs.getString("mapped_data"),
                    rs.getString("property_data")
                )
            );
        } catch(Exception e) {
            System.out.println(e.getMessage());
            return null;
        }
    }

    @Deprecated
    private InsertVertexEntity insertVertexQuery(long meta_id) {
        try {
            String sql = "WITH pk AS ("
                + " SELECT li.label_id, (SELECT column_name FROM tb_column_info WHERE column_id = si.source_pk_column_id) AS source_pk_column, pi.property_name AS meta_pk_column"
                + " FROM tb_label_info li"
                + " JOIN tb_property_info pi ON pi.label_id = li.label_id"
                + " JOIN tb_source_info si ON si.source_id = pi.source_id"
                + " WHERE li.meta_id = ?"
                + " AND li.use_yn = 'Y'"
                + " AND si.meta_key_yn = 'Y'"
                + " GROUP BY li.label_id, si.source_pk_column_id, pi.property_name"
                + " )"
                + " SELECT li.label_id, li.label_type, li.graph_name, li.target_label_name, pk.source_pk_column, pk.meta_pk_column, string_agg((SELECT column_name FROM tb_column_info ci JOIN tb_source_info si ON ci.column_id = si.column_id WHERE si.source_id = pi.source_id), ',') AS source_column_name, string_agg(pi.property_name, ',') AS property_name"
                + " FROM tb_label_info li"
                + " JOIN tb_property_info pi ON pi.label_id = li.label_id"
                + " JOIN pk ON pk.label_id = li.label_id"
                + " GROUP BY li.label_id, li.label_type, li.graph_name, li.target_label_name, pk.source_pk_column, pk.meta_pk_column";
            
            return jdbcTemplate.queryForObject(sql, new Object[] {meta_id}, (rs, rowNum) ->
                new InsertVertexEntity(
                    rs.getLong("label_id"),
                    rs.getString("label_type"),
                    rs.getString("graph_name"),
                    rs.getString("target_label_name"),
                    rs.getString("source_pk_column"),
                    rs.getString("meta_pk_column"),
                    rs.getString("source_column_name"),
                    rs.getString("property_name")
                )
            );
        } catch(Exception e) {
            System.out.println(e.getMessage());
            return null;
        }
    }

    @Deprecated
    public void updateData(JsonObject after, Map<String, String> map, String schemaName, String tableName) {
        List<MetaEntity> metaEntityList = selectMetaQuery(schemaName, tableName);
        if(metaEntityList.size() > 0) {
            for(int i=0; i<metaEntityList.size(); i++) {
                String[] metaData = metaEntityList.get(i).getMeta_data().split(",");
                String sourcePkColumn = metaData[2];
                String sourcePkValue = after.getAsJsonObject().get(sourcePkColumn).getAsString();
                String[] mappedData = metaEntityList.get(i).getMapped_data().toString().split("!");
                String[] mappedDataDetail = {};
                String[] propertyData = metaEntityList.get(i).getProperty_data().split(",");
                List<String> propertyList = new ArrayList<String>(Arrays.asList(propertyData));
                List<String> joinDataList = new ArrayList<String>();
                while(propertyList.remove(propertyData[0])) { }
                for(int j=3; j<metaData.length; j++) {
                    if(metaData[2].equals(metaData[j])) {
                        continue;
                    }
                    if(after.getAsJsonObject().get(metaData[j]) != null) {
                        joinDataList.add(after.getAsJsonObject().get(metaData[j]).toString());
                    }
                }
                for(int j=0; j<mappedData.length; j++) {
                    mappedDataDetail = mappedData[j].split(",");
                    for(int k=3; k<mappedDataDetail.length; k++) {
                        if(after.getAsJsonObject().get(mappedDataDetail[k]) != null) {
                            joinDataList.add(after.getAsJsonObject().get(mappedDataDetail[k]).toString());
                        }
                    }
                }

                String setClause = "";
                for(int j=0; j<joinDataList.size(); j++) {
                    if("".equals(setClause)) {
                        setClause = propertyList.get(j) + " = '" + joinDataList.get(j).replaceAll("\"", "") + "'::text";
                    } else {
                        setClause = setClause + ", " + propertyList.get(j) + " = '" + joinDataList.get(j).replaceAll("\"", "")  + "'::text";
                    }
                }
                
                String JOINQuery = "";
                for(int j=0; j<mappedData.length; j++) {
                    mappedDataDetail = mappedData[j].split(",");
                    String alias = " b" + j;
                    JOINQuery = JOINQuery +  " LEFT JOIN " + mappedDataDetail[0] + "." + mappedDataDetail[1] + alias
                        + " ON b." + sourcePkColumn + " =" + alias + "." + mappedDataDetail[2];
                }
                String metaSchema = metaEntityList.get(i).getMeta_schema_name();
                String metaTable = metaEntityList.get(i).getMeta_table_name();
                String updateForMetaSql = "UPDATE " + metaSchema + "." + metaTable + " a"
                    + " SET " + setClause
                    + " FROM " + metaData[0] + "." + metaData[1] + " b"
                    + JOINQuery
                    + " WHERE a." + propertyData[0] + " = " + after.getAsJsonObject().get(metaData[2]).toString().replaceAll("\"", "") + "::text";
                try {
                    jdbcTemplate.execute(updateForMetaSql);

                    UpdateVertexEntity updateVertexEntity = updateVertexQuery(metaEntityList.get(i).getMeta_id());
                    if(updateVertexEntity != null) {
                        String graphName = updateVertexEntity.getGraph_name();
                        String labelName = updateVertexEntity.getTarget_label_name();
                        String keyProperty = updateVertexEntity.getKey_property();
                        String conditions = " AND properties ->> '" + keyProperty + "' = '" + sourcePkValue + "'::text";

                        String vertexValue = updateVertexValueQuery(metaEntityList.get(i).getMeta_id(), metaSchema, metaTable, keyProperty, sourcePkValue);
                        JsonParser parser = new JsonParser();
                        JsonObject jobj = (JsonObject)parser.parse(vertexValue);
                        Iterator<String> iterator = jobj.keySet().iterator();
                        String setGraphClause = "";
                        while (iterator.hasNext()) {
                            String key = iterator.next();
                            String value = jobj.get(key).getAsString();
                            if("".equals(setGraphClause)) {
                                setGraphClause = "\"" + key + "\":\"" + value + "\"";
                            } else {
                                setGraphClause = setGraphClause + ", \"" + key + "\":\"" + value + "\"";
                            }
                        }

                        String updateSql = 
                            "UPDATE " + graphName + "." + labelName
                            + " SET properties = '{" + setGraphClause + "}'"
                            + " WHERE 1 = 1 " + conditions;
                        try {
                            jdbcTemplate.execute(updateSql);
                        } catch(Exception e) {
                            System.out.println(e.getMessage());
                        }
                    }
                } catch(Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        }
    }

    @Deprecated
    private UpdateVertexEntity updateVertexQuery(long meta_id) {
        try {
            String sql = "WITH pk AS ("
                + " SELECT mi.meta_id, si.source_id"
                + " FROM tb_meta_info mi"
                + " JOIN tb_source_info si ON si.meta_id = mi.meta_id"
                + " WHERE mi.meta_id = ?"
                + " AND si.meta_key_yn = 'Y'"
                + " AND si.source_pk_column_id = si.column_id"
                + " )"
                + " SELECT mi.meta_schema_name, mi.meta_table_name, li.graph_name, li.target_label_name, (SELECT property_name FROM tb_property_info WHERE source_id = pk.source_id) AS key_property"
                + " FROM tb_label_info li"
                + " JOIN tb_meta_info mi ON mi.meta_id = li.meta_id"
                + " JOIN tb_property_info pi ON pi.label_id = li.label_id"
                + " JOIN tb_source_info si ON si.source_id = pi.source_id"
                + " JOIN pk ON pk.meta_id = mi.meta_id"
                + " GROUP BY mi.meta_schema_name, mi.meta_table_name, li.graph_name, li.target_label_name, key_property";
            
            return jdbcTemplate.queryForObject(sql, new Object[] {meta_id}, (rs, rowNum) ->
                new UpdateVertexEntity(
                    rs.getString("meta_schema_name"),
                    rs.getString("meta_table_name"),
                    rs.getString("graph_name"),
                    rs.getString("target_label_name"),
                    rs.getString("key_property")
                )
            );
        } catch(Exception e) {
            System.out.println(e.getMessage());
            return null;
        }
    }

    @Deprecated
    private String updateVertexValueQuery(long meta_id, String metaSchema, String metaTable, String keyProperty, String sourcePkValue) {
        try {
            String sql = "SELECT row_to_json(tb.*) AS val"
                + " FROM " + metaSchema + "." + metaTable + " tb"
                + " WHERE tb." + keyProperty + " = " + sourcePkValue + "::text";
            return jdbcTemplate.queryForObject(sql, new Object[] {}, String.class);
        } catch(Exception e) {
            System.out.println(e.getMessage());
            return null;
        }
    }

    @Deprecated
    public void deleteData(JsonObject before, String schemaName, String tableName) {
        List<MetaEntity> metaEntityList = selectMetaQuery(schemaName, tableName);
        if(metaEntityList.size() > 0) {
            for(int i=0; i<metaEntityList.size(); i++) {
                String[] metaData = metaEntityList.get(i).getMeta_data().split(",");
                String sourcePkColumn = metaData[2];
                String sourcePkValue = before.getAsJsonObject().get(sourcePkColumn).getAsString();
                String[] propertyData = metaEntityList.get(i).getProperty_data().split(",");
                
                String metaSchema = metaEntityList.get(i).getMeta_schema_name();
                String metaTable = metaEntityList.get(i).getMeta_table_name();
                String deleteForMetaSql = "DELETE FROM " + metaSchema + "." + metaTable
                    + " WHERE " + propertyData[0] + " = " + before.getAsJsonObject().get(metaData[2]).toString().replaceAll("\"", "") + "::text";
                try {
                    jdbcTemplate.execute(deleteForMetaSql);

                    DeleteVertexEntity deleteVertexEntity = deleteVertexQuery(metaEntityList.get(i).getMeta_id());
                    if(deleteVertexEntity != null) {
                        String metaPkColumn = deleteVertexEntity.getMeta_pk_column();
                        String conditions = " AND properties ->> '" + metaPkColumn + "' = '" + sourcePkValue + "'::text";
                        
                        String graphName = deleteVertexEntity.getGraph_name();
                        String labelName = deleteVertexEntity.getTarget_label_name();

                        String sql = "DELETE FROM " + graphName + "." + labelName
                            + " WHERE 1 = 1 " + conditions;
                        try {
                            jdbcTemplate.execute(sql);
                        } catch(Exception e) {
                            System.out.println(e.getMessage());
                        }
                    }
                } catch(Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        }
    }

    @Deprecated
    private DeleteVertexEntity deleteVertexQuery(long meta_id) {
        try {
            String sql = "WITH pk AS ("
                + " SELECT li.label_id, pi.property_name AS meta_pk_column"
                + " FROM tb_label_info li"
                + " JOIN tb_property_info pi ON pi.label_id = li.label_id"
                + " JOIN tb_source_info si ON si.source_id = pi.source_id"
                + " WHERE li.meta_id = ?"
                + " AND li.use_yn = 'Y'"
                + " AND si.meta_key_yn = 'Y'"
                + " GROUP BY li.label_id, pi.property_name"
                + " )"
                + " SELECT li.label_id, li.label_type, li.graph_name, li.target_label_name, pk.meta_pk_column"
                + " FROM tb_label_info li"
                + " JOIN tb_property_info pi ON pi.label_id = li.label_id"
                + " JOIN pk ON pk.label_id = li.label_id"
                + " GROUP BY li.label_id, li.label_type, li.graph_name, li.target_label_name, pk.meta_pk_column";
            
            return jdbcTemplate.queryForObject(sql, new Object[] {meta_id}, (rs, rowNum) ->
                new DeleteVertexEntity(
                    rs.getLong("label_id"),
                    rs.getString("label_type"),
                    rs.getString("graph_name"),
                    rs.getString("target_label_name"),
                    rs.getString("meta_pk_column")
                )
            );
        } catch(Exception e) {
            System.out.println(e.getMessage());
            return null;
        }
    }

    /*
    @Deprecated
    public void insertEdgeData(JsonObject after, ColumnInfoEntity columnInfoEntity) {
        String startLb = after.getAsJsonObject().get("start_id").getAsString();
        String endLb = after.getAsJsonObject().get("end_id").getAsString();
        String conditions = " AND start_id = " + startLb + " AND end_id = " + endLb;
        
        String[] labelProp = labelInfoEntity.getTarget_label_properties().split(",");
        String buildmap = "";
        for(int i=0; i<labelProp.length; i++) {
            if(i > 0) {
                buildmap = buildmap + ", '" + labelProp[i] + "', a." + labelProp[i];
            } else {
                buildmap = "'" + labelProp[i] + "', data." + labelProp[i];
            }
        }
        String graphName = labelInfoEntity.getGraph_name();
        String targetLabelName = labelInfoEntity.getTarget_label_name();
        String startLabelName = labelInfoEntity.getStart_label_name();
        String startLabelId = labelInfoEntity.getStart_label_id();
        String endLabelName = labelInfoEntity.getEnd_label_name();
        String endLabelId = labelInfoEntity.getEnd_label_id();
        String targetStartLabelId = labelInfoEntity.getTarget_start_label_id();
        String targetEndLabelId = labelInfoEntity.getTarget_end_label_id();
        String colms = (labelInfoEntity.getSource_columns() != null ? ", " + labelInfoEntity.getSource_columns() : "");
        String rschem = labelInfoEntity.getSource_schema();
        String tbname = labelInfoEntity.getSource_table_name();

        String sql = 
            "INSERT INTO " + graphName + "." + targetLabelName
            + " select "
            + "  _graphid((_label_id('" + graphName + "'::name, '" + targetLabelName + "'::name))::integer, nextval('" + graphName + "." + targetLabelName + "_id_seq'::regclass)), "
            + "  data.startid::text::graphid, "
            + "  data.endid::text::graphid, "
            + "  agtype_build_map("+ buildmap +") "
            + "from "
            + "( "
            + "  SELECT startid, endid " + colms
            + "  FROM  "+ rschem +"."+ tbname + " tb "
            + "  JOIN cypher('" + graphName + "', $$ "
            + "    MATCH(v:" + startLabelName + ") RETURN id(v), v." + startLabelId
            + "  $$) AS a (startid agtype, " + startLabelId +" agtype) "
            + "  ON tb." + targetStartLabelId + " = a." + startLabelId
            + "  JOIN cypher('" + graphName + "', $$ "
            + "    MATCH(v:" + endLabelName + ") RETURN id(v), v." + endLabelId
            + "  $$) AS b (endid agtype, " + endLabelId +" agtype) "
            + "  ON tb." + targetEndLabelId + " = b." + endLabelId
            + "  WHERE 1 = 1 " + conditions
            + ") AS data;";
        jdbcTemplate.execute(sql);
    }
    */

    /*
    @Deprecated
    public void updateEdgeData(JsonObject after, ColumnInfoEntity columnInfoEntity, Map<String, String> map) {
        String startLb = after.getAsJsonObject().get("start_id").getAsString();
        String endLb = after.getAsJsonObject().get("end_id").getAsString();
        String startLabelId = labelInfoEntity.getStart_label_id();
        String endLabelId = labelInfoEntity.getEnd_label_id();
        String targetStartLabelId = labelInfoEntity.getTarget_start_label_id();
        String targetEndLabelId = labelInfoEntity.getTarget_end_label_id();
        String conditions = " AND a." + targetStartLabelId + " = b." + startLabelId
            + " AND a." + targetEndLabelId + " = " + endLabelId
            + " AND b.egid = c.id";
        
        String graphName = labelInfoEntity.getGraph_name();
        String targetLabelName = labelInfoEntity.getTarget_label_name();
        String sourceSchema = labelInfoEntity.getSource_schema();
        String sourceTableName = labelInfoEntity.getSource_table_name();
        String startLabelName = labelInfoEntity.getStart_label_name();
        String endLabelName = labelInfoEntity.getEnd_label_name();

        String[] labelProp = labelInfoEntity.getTarget_label_properties().split(",");
        String[] tbColumn = labelInfoEntity.getSource_columns().split(",");
        String setclaus = "";
        for(int i=0; i<labelProp.length; i++) {
            if(i > 0) {
                setclaus = setclaus + ", \"" + labelProp[i] + "\":" + after.getAsJsonObject().get(tbColumn[i]);
            } else {
                setclaus = "\"" + labelProp[i] + "\":" + after.getAsJsonObject().get(tbColumn[i]);
            }
        }

        String sql = 
            "update " + graphName + "." + targetLabelName + " c"
            + " set properties = concat('{" + setclaus + "}')::agtype"
            + " from " + sourceSchema + "." + sourceTableName + " a,"
            + "("
            + " select * from cypher('" + graphName + "', $$"
            + " match(v:" + startLabelName + ")-[e:" + targetLabelName + "]->(v2:" + endLabelName + ")"
            + " WHERE v." + startLabelId + " = " + startLb
            + " AND v2." + endLabelId + " = " + endLb
            + " return v." + startLabelId + ", id(e), v2." + endLabelId + ""
            + " $$) AS (" + startLabelId + " agtype, egid agtype, " + endLabelId + " agtype)"
            + ") b"
            + " WHERE 1 = 1 " + conditions;
        jdbcTemplate.execute(sql);
    }
    */

    /*
    @Deprecated
    public void deleteEdgeData(JsonObject before, ColumnInfoEntity columnInfoEntity) {
        String startLb = before.getAsJsonObject().get("start_id").getAsString();
        String endLb = before.getAsJsonObject().get("end_id").getAsString();
        String startLabelId = labelInfoEntity.getStart_label_id();
        String endLabelId = labelInfoEntity.getEnd_label_id();
        String conditions = " AND v." + startLabelId + " = " + startLb + " AND v2." + endLabelId + " = " + endLb;
        
        String graphName = labelInfoEntity.getGraph_name();
        String targetLabelName = labelInfoEntity.getTarget_label_name();
        String startLabelName = labelInfoEntity.getStart_label_name();
        String endLabelName = labelInfoEntity.getEnd_label_name();

        String sql = 
            "select * from cypher('" + graphName + "', $$"
            + "match(v:" + startLabelName + ")-[e:" + targetLabelName + "]->(v2:" + endLabelName + ")"
            + " WHERE 1 = 1 " + conditions
            + " detach delete e"
            + " $$) AS (e agtype)";
             */
        /* 테스트
         * select * from test.acted_in a,
            person b,
            movie c
            WHERE 1=1
            AND a.start_id = b.id
            AND a.end_id = c.id
            AND b.properties ->> 'act_no' = 7::text
            AND c.properties ->> 'movie_no' = 1003::text;
        //jdbcTemplate.execute(sql);
    }
    */

    private String getTimestampToDate(String timestampStr) {
        long timestamp = Long.parseLong(timestampStr);
        Date date = new java.util.Date(timestamp*1000L);
        SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT+9"));
        return sdf.format(date);
    }
}