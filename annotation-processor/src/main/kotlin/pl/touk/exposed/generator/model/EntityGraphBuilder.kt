package pl.touk.exposed.generator.model

import com.sun.org.apache.xml.internal.serialize.HTMLdtd.isBoolean
import jdk.nashorn.internal.runtime.JSType.isString
import pl.touk.exposed.generator.validation.GeneratedValueWithoutIdException
import javax.lang.model.type.TypeMirror
import javax.persistence.Column
import javax.persistence.Table
import pl.touk.exposed.generator.env.*
import pl.touk.exposed.generator.validation.EntityNotMappedException
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeKind
import javax.persistence.JoinColumn
import javax.persistence.OneToMany

class EntityGraphBuilder(
        private val typeEnv: TypeEnvironment, private val annEnv: AnnotationEnvironment
) {

    fun build(): EntityGraphs {
        val graphs = EntityGraphs()

        // TODO split
        for (entityElt in annEnv.entities) {
            val tableAnn = entityElt.getAnnotation(Table::class.java)
            val graph = graphs.getOrDefault(entityElt.packageName, EntityGraph())
            graph[entityElt] = EntityDefinition(
                    name = entityElt.simpleName, qualifiedName = entityElt.qualifiedName, table = tableAnn.name
            )
            graphs[entityElt.packageName] = graph
        }

        for (idElt in annEnv.ids) {
            val entityType = idElt.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)
            graph.computeIfPresent(entityType) { _, entity ->
                entity.copy(id = IdDefinition(idElt.simpleName))
            }
        }

        for (genValueElt in annEnv.genValues) {
            val entityType = genValueElt.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)
            graph.computeIfPresent(entityType) { _, entity ->
                val idDefinition = entity.id ?: throw GeneratedValueWithoutIdException(genValueElt, entityType)
                entity.copy(id = idDefinition.copy(generatedValue = true))
            }
        }

        for (columnElt in annEnv.columns) {
            val entityType = columnElt.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)
            graph.computeIfPresent(entityType) { _, entity ->
                val columnAnn = columnElt.getAnnotation(Column::class.java)
                // TODO nullable
//                val isNotNull = columnElt.annotationMirrors.any {
//                    (it as DeclaredType).asElement().toTypeElement().qualifiedName.contentEquals(NotNull::class.java.canonicalName)
//                }
                val type = columnElt.asType().getTypeDefinition()
                val columnDefinition = PropertyDefinition(name = columnElt.simpleName, annotation = columnAnn, type = type, typeMirror = columnElt.asType())
                entity.addProperty(columnDefinition)
            }
        }

        for (oneToMany in annEnv.oneToMany) {
            val entityType = oneToMany.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)
            val otmAnn = oneToMany.getAnnotation(OneToMany::class.java)
            val target = oneToMany.asType().getTypeArgument().asElement().toTypeElement()
            graph.computeIfPresent(entityType) { _, entity ->
                val associationDef = AssociationDefinition(
                        name = oneToMany.simpleName, type = AssociationType.ONE_TO_MANY,
                        target = target, mappedBy = otmAnn.mappedBy
                )
                entity.addAssociation(associationDef)
            }
        }

        for (manyToOne in annEnv.manyToOne) {
            val entityType = manyToOne.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)
            graph.computeIfPresent(entityType) { _, entity ->
                val join = manyToOne.getAnnotation(JoinColumn::class.java)
                val target = manyToOne.toVariableElement().asType().asDeclaredType().asElement().toTypeElement()
                val associationDef = AssociationDefinition(
                        name = manyToOne.simpleName, type = AssociationType.MANY_TO_ONE,
                        target = target, joinColumn = join.name
                )
                entity.addAssociation(associationDef)
            }
        }

        for (oneToMany in annEnv.oneToMany) {
            val entityType = oneToMany.enclosingTypeElement()

            val joinColumnAnn = oneToMany.getAnnotation(JoinColumn::class.java) ?: continue
            val targetType = oneToMany.asType().getTypeArgument().asElement().toTypeElement()

            val graph = graphs[targetType.packageName] ?: throw EntityNotMappedException(targetType)
            graph.computeIfPresent(targetType) { _, entity ->
                val isMapped = entity.associations.any { assoc -> assoc.type == AssociationType.MANY_TO_ONE && assoc.target == entityType }
                if (isMapped) {
                    entity
                } else {
                    val associationDef = AssociationDefinition(
                            name = entityType.simpleName, type = AssociationType.MANY_TO_ONE,
                            target = entityType, joinColumn = joinColumnAnn.name, mapped = false
                    )
                    entity.addAssociation(associationDef)
                }
            }
        }
        return graphs
    }

    private fun TypeMirror.asDeclaredType(): DeclaredType {
        require(this is DeclaredType)
        return this
    }

    private fun TypeMirror.getTypeArgument(): DeclaredType {
        return this.asDeclaredType().typeArguments[0].asDeclaredType()
    }

    private fun TypeMirror.getTypeDefinition() : PropertyType {
        return when {
            isString() -> PropertyType.STRING
            isBoolean() -> PropertyType.BOOL
            isNumeric() -> PropertyType.LONG
            else -> TODO()
        }
    }

    private fun TypeMirror.isString() = typeEnv.isSameType(this, "java.lang.String")

    private fun TypeMirror.isBoolean() = typeEnv.isSameType(this, "java.lang.Boolean") || kind == TypeKind.BOOLEAN

    // TODO float/int/long/double
    private fun TypeMirror.isNumeric() = typeEnv.isSubType(this, "java.lang.Number") ||
            kind in listOf(TypeKind.LONG, TypeKind.INT, TypeKind.DOUBLE, TypeKind.FLOAT, TypeKind.SHORT)

}
