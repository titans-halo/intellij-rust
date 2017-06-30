/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.ty

import com.intellij.openapi.project.Project
import org.rust.lang.core.psi.RsBaseType
import org.rust.lang.core.psi.RsTraitItem
import org.rust.lang.core.psi.RsTypeParameter
import org.rust.lang.core.psi.ext.RsGenericDeclaration
import org.rust.lang.core.psi.ext.flattenHierarchy
import org.rust.lang.core.psi.ext.resolveToBoundTrait
import org.rust.lang.core.types.BoundElement

class TyTypeParameter private constructor(
    private val parameter: TypeParameter,
    private val name: String?,
    private val bounds: Collection<BoundElement<RsTraitItem>>
) : Ty {

    constructor(parameter: RsTypeParameter) : this(Named(parameter), parameter.name, bounds(parameter))
    constructor(trait: RsTraitItem) : this(Self(trait), "Self", listOf(BoundElement(trait)))
    constructor(trait: RsTraitItem, target: String) : this(
        AssociatedType(trait, target),
        "${trait.name}::target",
        emptyList()
    )

    override fun equals(other: Any?): Boolean = other is TyTypeParameter && other.parameter == parameter
    override fun hashCode(): Int = parameter.hashCode()

    fun getTraitBoundsTransitively(): Collection<BoundElement<RsTraitItem>> =
        bounds.flatMap { it.flattenHierarchy }

    override fun canUnifyWith(other: Ty, project: Project, mapping: TypeMapping?): Boolean {
        mapping?.merge(mutableMapOf(this to other))
        return true
    }

    override fun substitute(map: TypeArguments): Ty = map[this] ?: TyTypeParameter(parameter, name, bounds.map {
        BoundElement(it.element, it.typeArguments.substituteInValues(map))
    })

    override fun toString(): String = name ?: "<unknown>"

    private interface TypeParameter
    private data class Named(val parameter: RsTypeParameter) : TypeParameter
    private data class Self(val trait: RsTraitItem) : TypeParameter
    private data class AssociatedType(val trait: RsTraitItem, val target: String) : TypeParameter
}

private fun bounds(parameter: RsTypeParameter): List<BoundElement<RsTraitItem>> {
    val owner = parameter.parent?.parent as? RsGenericDeclaration
    val whereBounds =
        owner?.whereClause?.wherePredList.orEmpty()
            .filter { (it.typeReference as? RsBaseType)?.path?.reference?.resolve() == parameter }
            .flatMap { it.typeParamBounds?.polyboundList.orEmpty() }

    return (parameter.typeParamBounds?.polyboundList.orEmpty() + whereBounds)
        .mapNotNull { it.bound.traitRef?.resolveToBoundTrait }
}
