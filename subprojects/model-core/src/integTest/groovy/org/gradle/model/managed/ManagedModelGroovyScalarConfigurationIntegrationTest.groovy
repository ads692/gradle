/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.model.managed

import groovy.transform.NotYetImplemented
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Ignore
import spock.lang.Unroll

import static org.hamcrest.CoreMatchers.containsString

// TODO:BB all tests have @NotYetImplemented because they pass in isolation, but other tests fail with
//         the temporary CharSequenceToScalarConverter because the wrong exceptions are thrown
class ManagedModelGroovyScalarConfigurationIntegrationTest extends AbstractIntegrationSpec {

    private static final String CLASSES = '''
        enum Thing {
            TOASTER,
            NOT_A_TOASTER
        }

        @Managed
        interface Props {
            Thing getTheThing()
            void setTheThing(Thing t)

            boolean isBool1()
            void setBool1(boolean b)

            boolean isBool2()
            void setBool2(boolean b)

            Boolean getTheBoolean()
            void setTheBoolean(Boolean b)

            byte getThebyte()
            void setThebyte(byte b)

            char getThechar()
            void setThechar(char c)

            Character getTheCharacter()
            void setTheCharacter(Character c)

            int getTheint()
            void setTheint(int i)

            Integer getTheInteger()
            void setTheInteger(Integer i)

            short getTheshort()
            void setTheshort(short s)

            String getTheString()
            void setTheString(String s)

            void setTheByte(Byte b)
            Byte getTheByte()

            Short getTheShort()
            void setTheShort(Short s)

            float getThefloat()
            void setThefloat(float f)

            Float getTheFloat()
            void setTheFloat(Float f)

            Long getTheLong()
            void setTheLong(Long l)

            long getThelong()
            void setThelong(long l)

            Double getTheDouble()
            void setTheDouble(Double d)

            double getThedouble()
            void setThedouble(double d)

            BigInteger getTheBigInteger()
            void setTheBigInteger(BigInteger b)

            BigDecimal getTheBigDecimal()
            void setTheBigDecimal(BigDecimal b)
        }

        class RulePlugin extends RuleSource {
            @Model
            void props(Props p) {}

            @Mutate
            void addTask(ModelMap<Task> tasks, Props p) {
                tasks.create('printResolvedValues') {
                    doLast {
                        println "prop theBigDecimal: $p.theBigDecimal :"
                        println "prop theBigInteger: $p.theBigInteger :"
                        println "prop bool1        : $p.bool1 :"
                        println "prop bool2        : $p.bool2 :"
                        println "prop theBoolean   : $p.theBoolean :"
                        println "prop thebyte      : $p.thebyte :"
                        println "prop theByte      : $p.theByte :"
                        println "prop thechar      : $p.thechar :"
                        println "prop theCharacter : $p.theCharacter :"
                        println "prop theDouble    : $p.theDouble :"
                        println "prop thedouble    : $p.thedouble :"
                        println "prop thefloat     : $p.thefloat :"
                        println "prop theFloat     : $p.theFloat :"
                        println "prop theint       : $p.theint :"
                        println "prop theInteger   : $p.theInteger :"
                        println "prop theLong      : $p.theLong :"
                        println "prop thelong      : $p.thelong :"
                        println "prop theshort     : $p.theshort :"
                        println "prop theShort     : $p.theShort :"
                        println "prop theString    : $p.theString :"
                        println "prop theThing     : $p.theThing :"
                    }
                }
            }
        }

        apply type: RulePlugin
        '''

    @NotYetImplemented
    @Unroll
    void 'only CharSequence input values are supported'() {
        when:
        buildFile << CLASSES
        buildFile << """
            model {
                props {
                    $varname = new Object()
                }
            }
            """

        then:
        fails 'printResolvedValues'

        and:
        failure.assertThatCause(containsString('Unsupported type'))
        failure.assertThatCause(containsString('The following types/formats are supported:'))
        failure.assertThatCause(containsString('CharSequence instances'))

        where:
        varname << ['theBigDecimal', 'theBigInteger', 'bool1', 'bool2', 'theBoolean', 'theDouble', 'thedouble',
                    'thefloat', 'theFloat', 'theint', 'theInteger', 'theLong', 'thelong', 'theshort', 'theShort',
                    'thebyte', 'theByte', 'thechar', 'theCharacter', 'theString', 'theThing']
    }

    @NotYetImplemented
    @Unroll
    void 'number types require stringified numeric inputs'() {
        when:
        buildFile << CLASSES
        buildFile << """
            model {
                props {
                    $varname = '${value}foo'
                }
            }
            """

        then:
        fails 'printResolvedValues'

        and:
        failure.assertThatCause(containsString("Cannot coerce string value '42foo' to type $type.simpleName"))

        // TODO:BB uncomment primitive cases after updating the method signature to accept a boolean 3rd arg indicating that the type is primitive
        where:
        varname         | value | type
        'theBigDecimal' | 42    | BigDecimal
        'theBigInteger' | 42    | BigInteger
        'theDouble'     | 42    | Double
//        'thedouble'     | 42    | double
//        'thefloat'      | 42    | float
        'theFloat'      | 42    | Float
//        'theint'        | 42    | int
        'theInteger'    | 42    | Integer
        'theLong'       | 42    | Long
//        'thelong'       | 42    | long
//        'theshort'      | 42    | short
        'theShort'      | 42    | Short
//        'thebyte'       | 42    | byte
        'theByte'       | 42    | Byte
    }

    @NotYetImplemented
    void 'enum types require valid enum constants'() {
        when:
        buildFile << CLASSES
        buildFile << """
            model {
                props {
                    theThing = 'IS_NOT_A_TOASTER'
                }
            }
            """

        then:
        fails 'printResolvedValues'

        and:
        failure.assertThatCause(containsString("Cannot coerce string value 'IS_NOT_A_TOASTER' to an enum value of type 'Thing'"))
    }

    @Ignore // can't use NotYetImplemented here
    @Unroll
    void 'boolean types are only true for the literal string "true"'() {
        when:
        buildFile << CLASSES
        buildFile << """
            model {
                props {
                    bool1 = '$value'
                }
            }
            """

        then:
        succeeds 'printResolvedValues'

        and:
        output.contains "prop bool1        : $actual"

        where:
        value   | actual
        'true'  | true
        'TRUE'  | false
        'false' | false
    }

    @NotYetImplemented
    void 'can convert CharSequence to any scalar type'() {
        when:
        buildFile << CLASSES
        buildFile << '''
            model {
                props {
                    theBigDecimal = "${new BigDecimal('123.4').power(10)}"
                    theBigInteger = "${(Long.MAX_VALUE as BigInteger) * 10}"
                    bool1 = "${1 > 2}"
                    bool2 = "${2 > 1}"
                    theBoolean = "${3 > 2}"
                    theDouble = "${Math.PI - 1}"
                    thedouble = "${Math.E - 1}"
                    thefloat = "${Math.PI - 2}"
                    theFloat = "${Math.E - 2}"
                    theint = "${1 + 2}"
                    theInteger = "${3 + 5}"
                    theLong = "${(long)Integer.MAX_VALUE * 2}"
                    thelong = "${(long)Integer.MAX_VALUE * 3}"
                    theshort = "${8 + 13}"
                    theShort = "${21 + 34}"
                    thebyte = "55"
                    theByte = "89"
                    thechar = "${'managed'[3]}"
                    theCharacter = "${'managed'[4]}"
                    theString = "${'bar/fooooo' - 'ooo'}"
                    theThing = "${Thing.valueOf('NOT_A_TOASTER')}"
                }
            }
        '''

        then:
        succeeds 'printResolvedValues'

        and:
        output.contains 'prop theBigDecimal: 818750535356720922824.4052427776'
        output.contains 'prop theBigInteger: 92233720368547758070'
        output.contains 'prop bool1        : false'
        output.contains 'prop bool2        : true'
        output.contains 'prop theBoolean   : true'
        output.contains 'prop theDouble    : 2.141592653589793'
        output.contains 'prop thedouble    : 1.718281828459045'
        output.contains 'prop thefloat     : 1.1415926'
        output.contains 'prop theFloat     : 0.7182818'
        output.contains 'prop theint       : 3'
        output.contains 'prop theInteger   : 8'
        output.contains 'prop theLong      : 4294967294'
        output.contains 'prop thelong      : 6442450941'
        output.contains 'prop theshort     : 21'
        output.contains 'prop theShort     : 55'
        output.contains 'prop thebyte      : 55'
        output.contains 'prop theByte      : 89'
        output.contains 'prop thechar      : a'
        output.contains 'prop theCharacter : g'
        output.contains 'prop theString    : bar/foo'
        output.contains 'prop theThing     : NOT_A_TOASTER'
    }
}
