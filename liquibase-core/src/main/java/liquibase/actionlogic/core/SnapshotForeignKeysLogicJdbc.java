package liquibase.actionlogic.core;

import liquibase.Scope;
import liquibase.action.Action;
import liquibase.action.core.QueryJdbcMetaDataAction;
import liquibase.action.core.SnapshotDatabaseObjectsAction;
import liquibase.actionlogic.ActionResult;
import liquibase.actionlogic.ObjectBasedQueryResult;
import liquibase.actionlogic.RowBasedQueryResult;
import liquibase.exception.ActionPerformException;
import liquibase.structure.DatabaseObject;
import liquibase.structure.ObjectName;
import liquibase.structure.ObjectReference;
import liquibase.structure.core.*;
import liquibase.util.Validate;

import java.sql.DatabaseMetaData;
import java.util.*;

public class SnapshotForeignKeysLogicJdbc extends AbstractSnapshotDatabaseObjectsLogicJdbc {

    @Override
    protected Class<? extends DatabaseObject> getTypeToSnapshot() {
        return ForeignKey.class;
    }

    @Override
    protected Class<? extends DatabaseObject>[] getSupportedRelatedTypes() {
        return new Class[]{
                ForeignKey.class,
                Relation.class,
                Schema.class,
                Catalog.class
        };
    }

    @Override
    protected Action createSnapshotAction(SnapshotDatabaseObjectsAction action, Scope scope) throws ActionPerformException {
        ObjectReference relatedTo = action.relatedTo;
        ObjectName fkName = null;
        ObjectName tableName = null;
        if (relatedTo.instanceOf(Catalog.class)) {
            if (scope.getDatabase().getMaxSnapshotContainerDepth() < 2) {
                throw new ActionPerformException("Cannot snapshot catalogs on " + scope.getDatabase().getShortName());
            }
            fkName = new ObjectName(relatedTo.getSimpleName(), null, null, null);
        } else if (relatedTo.instanceOf(Schema.class)) {
            fkName = new ObjectName(new ObjectName(new ObjectName(relatedTo.objectName.container, relatedTo.getSimpleName()), null), null);
        } else if (relatedTo.instanceOf(Table.class)) {
            tableName = relatedTo.objectName;
        } else if (relatedTo.instanceOf(ForeignKey.class)) {
            fkName = relatedTo.objectName;
        } else {
            throw Validate.failure("Unexpected relatedTo type: " + relatedTo.objectType.getName());
        }

        if (tableName == null) {
            tableName = new ObjectName(fkName.container, null);
        }

        tableName = tableName.truncate(scope.getDatabase().getMaxSnapshotContainerDepth() + 2);
        List<String> nameParts = tableName.asList(3);

        if (scope.getDatabase().getMaxSnapshotContainerDepth() >= 2) {
            return new QueryJdbcMetaDataAction("getImportedKeys", nameParts.get(0), nameParts.get(1), nameParts.get(2));
        } else { //usually calls the top level "catalogs"
            return new QueryJdbcMetaDataAction("getImportedKeys", nameParts.get(1), null, nameParts.get(2));
        }
    }

    @Override
    protected DatabaseObject convertToObject(RowBasedQueryResult.Row row, SnapshotDatabaseObjectsAction originalAction, Scope scope) throws ActionPerformException {
        String pkTableCat = row.get("PKTABLE_CAT", String.class);
        String pkTableSchema = row.get("PKTABLE_SCHEM", String.class);
        String pkTableName = row.get("PKTABLE_NAME", String.class);
        String pkColumnName = row.get("PKCOLUMN_NAME", String.class);

        String fkTableCat = row.get("FKTABLE_CAT", String.class);
        String fkTableSchema = row.get("FKTABLE_SCHEM", String.class);
        String fkTableName = row.get("FKTABLE_NAME", String.class);
        String fkColumnName = row.get("FKCOLUMN_NAME", String.class);
        Integer position = row.get("KEY_SEQ", Integer.class);
        Short updateRule = row.get("UPDATE_RULE", Short.class);
        Short deleteRule = row.get("DELETE_RULE", Short.class);
        String fkName = row.get("FK_NAME", String.class);
        Short deferrability = row.get("DEFERRABILITY", Short.class);

        ObjectName objectName;
        if (fkTableCat != null && fkTableSchema == null) {
            objectName = new ObjectName(fkTableCat, fkName);
        } else {
            objectName = new ObjectName(fkTableCat, fkTableSchema, fkName);
        }
        ObjectName fkTableObjectName;
        if (fkTableCat != null && fkTableSchema == null) {
            fkTableObjectName = new ObjectName(fkTableCat, fkTableName);
        } else {
            fkTableObjectName = new ObjectName(fkTableCat, fkTableSchema, fkTableName);
        }
        ObjectName pkTableObjectName;
        if (pkTableCat != null && pkTableSchema == null) {
            pkTableObjectName = new ObjectName(pkTableCat, pkTableName);
        } else {
            pkTableObjectName = new ObjectName(pkTableCat, pkTableSchema, pkTableName);
        }

        ForeignKey fk = new ForeignKey(objectName);
        fk.columnChecks.add(new ForeignKey.ForeignKeyColumnCheck(new ObjectName(fkTableObjectName, fkColumnName), new ObjectName(pkTableObjectName, pkColumnName), position));

        if (updateRule != null) {
            switch (updateRule) {
                case(DatabaseMetaData.importedKeyNoAction):
                    fk.updateRule = ForeignKeyConstraintType.importedKeyNoAction;
                    break;
                case(DatabaseMetaData.importedKeyCascade):
                    fk.updateRule = ForeignKeyConstraintType.importedKeyCascade;
                    break;
                case(DatabaseMetaData.importedKeySetNull):
                    fk.updateRule = ForeignKeyConstraintType.importedKeySetNull;
                    break;
                case(DatabaseMetaData.importedKeySetDefault):
                    fk.updateRule = ForeignKeyConstraintType.importedKeySetDefault;
                    break;
                case(DatabaseMetaData.importedKeyRestrict):
                    fk.updateRule = ForeignKeyConstraintType.importedKeyRestrict;
                    break;
            }
        }

        if (deleteRule != null) {
            switch (deleteRule) {
                case(DatabaseMetaData.importedKeyNoAction):
                    fk.deleteRule = ForeignKeyConstraintType.importedKeyNoAction;
                    break;
                case(DatabaseMetaData.importedKeyCascade):
                    fk.deleteRule = ForeignKeyConstraintType.importedKeyCascade;
                    break;
                case(DatabaseMetaData.importedKeySetNull):
                    fk.deleteRule = ForeignKeyConstraintType.importedKeySetNull;
                    break;
                case(DatabaseMetaData.importedKeySetDefault):
                    fk.deleteRule = ForeignKeyConstraintType.importedKeySetDefault;
                    break;
                case(DatabaseMetaData.importedKeyRestrict):
                    fk.deleteRule = ForeignKeyConstraintType.importedKeyRestrict;
                    break;
            }
        }

        if (deferrability != null) {
            switch (deferrability) {
                case (DatabaseMetaData.importedKeyInitiallyDeferred):
                    fk.deferrable = true;
                    fk.initiallyDeferred = true;
                    break;
                case (DatabaseMetaData.importedKeyInitiallyImmediate):
                    fk.deferrable = true;
                    fk.initiallyDeferred = false;
                    break;
                case (DatabaseMetaData.importedKeyNotDeferrable):
                    fk.deferrable = false;
                    fk.initiallyDeferred = false;
                    break;
            }
        }
        return fk;
}

    @Override
    protected ActionResult.Modifier createModifier(SnapshotDatabaseObjectsAction originalAction, Scope scope) {
        return new SnapshotModifier(originalAction, scope) {
            @Override
            public ActionResult rewrite(ActionResult result) throws ActionPerformException {
                List<ForeignKey> rawResults = ((ObjectBasedQueryResult) super.rewrite(result)).asList(ForeignKey.class);
                Map<ObjectName, ForeignKey> combinedResults = new HashMap<>();
                for (ForeignKey foreignKey : rawResults) {
                    ForeignKey existingPk = combinedResults.get(foreignKey.name);
                    if (existingPk == null) {
                        combinedResults.put(foreignKey.name, foreignKey);
                    } else {
                        existingPk.columnChecks.addAll(foreignKey.columnChecks);
                    }
                }

                for (ForeignKey foreignKey : combinedResults.values()) {
                    Collections.sort(foreignKey.columnChecks, new Comparator<ForeignKey.ForeignKeyColumnCheck>() {
                        @Override
                        public int compare(ForeignKey.ForeignKeyColumnCheck o1, ForeignKey.ForeignKeyColumnCheck o2) {
                            if (o1.position == null || o2.position == null) {
                                return o1.baseColumn.name.compareTo(o2.baseColumn.name);
                            } else {
                                return o1.position.compareTo(o2.position);
                            }
                        }
                    });
                }

                return new ObjectBasedQueryResult(new ArrayList(combinedResults.values()));
            }
        };
    }
}
