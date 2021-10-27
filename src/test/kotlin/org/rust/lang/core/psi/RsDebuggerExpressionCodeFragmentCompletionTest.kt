/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi

import org.rust.ProjectDescriptor
import org.rust.WithStdlibRustProjectDescriptor

class RsDebuggerExpressionCodeFragmentCompletionTest
    : RsCodeFragmentCompletionTestBase(::RsDebuggerExpressionCodeFragment) {

    fun `test complete public field`() = checkContainsCompletion("""
        mod my {
            pub struct Foo { pub inner: i32 }
        }
        fn bar(foo: my::Foo) {
            /*caret*/;
        }
    """, "foo.in<caret>", "inner")

    fun `test complete private field`() = checkContainsCompletion("""
        mod my {
            pub struct Foo { inner: i32 }
        }
        fn bar(foo: my::Foo) {
            /*caret*/;
        }
    """, "foo.in<caret>", "inner")

    fun `test complete public function`() = checkContainsCompletion("""
        mod my {
            pub fn foobar() {}
        }
        fn bar() {
            use my::*;
            /*caret*/;
        }
    """, "foo<caret>", "foobar")

    fun `test complete private function`() = checkContainsCompletion("""
        mod my {
            fn foobar() {}
        }
        fn bar() {
            use my::*;
            /*caret*/;
        }
    """, "foo<caret>", "foobar")

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test complete Vec public method`() = checkContainsCompletion("""
        fn main() {
            let xs = vec![1,2,3];
            /*caret*/;
        }
    """, "xs.<caret>", "len")

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test complete Vec private field`() = checkContainsCompletion("""
        fn main() {
            let xs = vec![1,2,3];
            /*caret*/;
        }
    """, "xs.<caret>", "buf")
}
