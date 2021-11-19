/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring

import org.intellij.lang.annotations.Language
import org.rust.RsTestBase
import org.rust.lang.core.psi.ext.RsItemElement
import org.rust.lang.core.psi.ext.ancestorOrSelf

class RsExtractTraitTest : RsTestBase() {

    fun `test one method 1`() = doTest("""
        struct S;
        impl S {
            /*caret*/fn a(&self) {}
        }
    """, """
        struct S;

        impl Trait for S {
            fn a(&self) {}
        }

        trait Trait {
            fn a(&self);
        }
    """)

    fun `test one method 2`() = doTest("""
        struct S;
        impl S {
            /*caret*/fn a(&self) {}
            fn b(&self) {}
        }
    """, """
        struct S;
        impl S {
            fn b(&self) {}
        }

        impl Trait for S {
            fn a(&self) {}
        }

        trait Trait {
            fn a(&self);
        }
    """)

    fun `test two methods`() = doTest("""
        struct S;
        impl S {
            /*caret*/fn a(&self) {}
            /*caret*/fn b(&self) {}
        }
    """, """
        struct S;

        impl Trait for S {
            fn a(&self) {}
            fn b(&self) {}
        }

        trait Trait {
            fn a(&self);
            fn b(&self);
        }
    """)

    fun `test public method`() = doTest("""
        struct S;
        impl S {
            /*caret*/pub fn a(&self) {}
        }
    """, """
        struct S;

        impl Trait for S {
            fn a(&self) {}
        }

        trait Trait {
            fn a(&self);
        }
    """)

    fun `test associated constant`() = doTest("""
        struct S;
        impl S {
            /*caret*/const C: i32 = 0;
        }
    """, """
        struct S;

        impl Trait for S {
            const C: i32 = 0;
        }

        trait Trait {
            const C: i32;
        }
    """)

    fun `test associated type`() = doTest("""
        struct S;
        impl S {
            /*caret*/type T = i32;
        }
    """, """
        struct S;

        impl Trait for S {
            type T = i32;
        }

        trait Trait {
            type T;
        }
    """)

    fun `test generics 1`() = doTest("""
        struct S<A> { a: A }
        impl<A> S<A> {
            /*caret*/fn get_a(&self) -> &A { &self.a }
        }
    """, """
        struct S<A> { a: A }

        impl<A> Trait<A> for S<A> {
            fn get_a(&self) -> &A { &self.a }
        }

        trait Trait<A> {
            fn get_a(&self) -> &A;
        }
    """)

    fun `test generics 2`() = doTest("""
        struct S<A> { a: A }
        impl<A> S<A> {
            fn get_a(&self) -> &A { &self.a }
            /*caret*/fn b(&self) {}
        }
    """, """
        struct S<A> { a: A }
        impl<A> S<A> {
            fn get_a(&self) -> &A { &self.a }
        }

        impl<A> Trait for S<A> {
            fn b(&self) {}
        }

        trait Trait {
            fn b(&self);
        }
    """)

    fun `test generics copmlex`() = doTest("""
        struct S<A, B> { a: A, b: B }
        impl<A, B> S<A, B> where A: BoundA, B: BoundB {
            /*caret*/fn get_a(&self) -> &A { &self.a }
            fn get_b(&self) -> &B { &self.b }
        }
    """, """
        struct S<A, B> { a: A, b: B }
        impl<A, B> S<A, B> where A: BoundA, B: BoundB {
            fn get_b(&self) -> &B { &self.b }
        }

        impl<A, B> Trait<A> for S<A, B> where A: BoundA, B: BoundB {
            fn get_a(&self) -> &A { &self.a }
        }

        trait Trait<A> where A: BoundA {
            fn get_a(&self) -> &A;
        }
    """)

    fun `test generics in associated type`() = doTest("""
        struct S<A> { a: A }
        impl<A> S<A> {
            /*caret*/type T = A;
        }
    """, """
        struct S<A> { a: A }

        impl<A> Trait for S<A> {
            type T = A;
        }

        trait Trait {
            type T;
        }
    """)

    fun `test generics in function 1`() = doTest("""
        struct S<A> { a: A }
        impl<A> S<A> {
            /*caret*/fn func(&self) {
                A::C;
            }
        }
    """, """
        struct S<A> { a: A }

        impl<A> Trait for S<A> {
            fn func(&self) {
                A::C;
            }
        }

        trait Trait {
            fn func(&self);
        }
    """)

    fun `test generics in function 2`() = doTest("""
        struct S<A> { a: A }
        impl<A> S<A> {
            /*caret*/fn func(&self, a: A) {}
        }
    """, """
        struct S<A> { a: A }

        impl<A> Trait<A> for S<A> {
            fn func(&self, a: A) {}
        }

        trait Trait<A> {
            fn func(&self, a: A);
        }
    """)

    fun `test generics in function 3`() = doTest("""
        struct S<A> { a: A }
        impl<A> S<A> {
            /*caret*/fn func(&self) -> A { unimplemented!() }
        }
    """, """
        struct S<A> { a: A }

        impl<A> Trait<A> for S<A> {
            fn func(&self) -> A { unimplemented!() }
        }

        trait Trait<A> {
            fn func(&self) -> A;
        }
    """)

    fun `test not available for trait impl`() = doUnavailableTest("""
        struct S;
        impl T for S {
            /*caret*/fn a(&self) {}
        }
    """)

    fun `test not available if no members`() = doUnavailableTest("""
        struct S;
        impl T for S { /*caret*/ }
    """)

    private fun doTest(
        @Language("Rust") before: String,
        @Language("Rust") after: String
    ) {
        check("/*caret*/" in before)
        checkByText(before.trimIndent(), after.trimIndent()) {
            markItemsUnderCarets()
            myFixture.performEditorAction("ExtractInterface")
        }
    }

    private fun doUnavailableTest(@Language("Rust") before: String) {
        check("/*caret*/" in before)
        checkByText(before.trimIndent(), before.trimIndent()) {
            markItemsUnderCarets()
            myFixture.performEditorAction("ExtractInterface")
        }
    }

    private fun markItemsUnderCarets() {
        for (caret in myFixture.editor.caretModel.allCarets) {
            val element = myFixture.file.findElementAt(caret.offset)!!
            val item = element.ancestorOrSelf<RsItemElement>()!!
            item.putUserData(RS_EXTRACT_TRAIT_MEMBER_IS_SELECTED, true)
        }
    }
}
