/*
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.core.format.printf;

import com.oracle.truffle.api.object.DynamicObject;
import org.antlr.v4.runtime.Token;
import org.jcodings.specific.USASCIIEncoding;
import org.jruby.truffle.RubyContext;
import org.jruby.truffle.core.format.FormatNode;
import org.jruby.truffle.core.format.LiteralFormatNode;
import org.jruby.truffle.core.format.control.SequenceNode;
import org.jruby.truffle.core.format.convert.ToDoubleWithCoercionNodeGen;
import org.jruby.truffle.core.format.convert.ToIntegerNodeGen;
import org.jruby.truffle.core.format.convert.ToStringNodeGen;
import org.jruby.truffle.core.format.format.FormatIntegerBinaryNodeGen;
import org.jruby.truffle.core.format.format.FormatFloatHumanReadableNodeGen;
import org.jruby.truffle.core.format.format.FormatFloatNodeGen;
import org.jruby.truffle.core.format.format.FormatIntegerNodeGen;
import org.jruby.truffle.core.format.read.SourceNode;
import org.jruby.truffle.core.format.read.array.ReadArgumentIndexValueNodeGen;
import org.jruby.truffle.core.format.read.array.ReadHashValueNodeGen;
import org.jruby.truffle.core.format.read.array.ReadIntegerNodeGen;
import org.jruby.truffle.core.format.read.array.ReadStringNodeGen;
import org.jruby.truffle.core.format.read.array.ReadValueNodeGen;
import org.jruby.truffle.core.format.write.bytes.WriteByteNodeGen;
import org.jruby.truffle.core.format.write.bytes.WriteBytesNodeGen;
import org.jruby.truffle.core.format.write.bytes.WritePaddedBytesNodeGen;
import org.jruby.truffle.core.rope.CodeRange;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PrintfTreeBuilder extends PrintfParserBaseListener {

    public static final int PADDING_FROM_ARGUMENT = -2;

    public static final int DEFAULT = -1;

    private final RubyContext context;
    private final byte[] source;

    private final List<FormatNode> sequence = new ArrayList<>();

    private static final byte[] EMPTY_BYTES = new byte[]{};

    public PrintfTreeBuilder(RubyContext context, byte[] source) {
        this.context = context;
        this.source = source;
    }

    @Override
    public void exitEscaped(PrintfParser.EscapedContext ctx) {
        sequence.add(WriteByteNodeGen.create(context, new LiteralFormatNode(context, (byte) '%')));
    }

    @Override
    public void exitString(PrintfParser.StringContext ctx) {
        final byte[] keyBytes = tokenAsBytes(ctx.CURLY_KEY().getSymbol(), 1);
        final DynamicObject key = context.getSymbolTable().getSymbol(context.getRopeTable().getRope(keyBytes, USASCIIEncoding.INSTANCE, CodeRange.CR_7BIT));

        sequence.add(
                WriteBytesNodeGen.create(context,
                        ToStringNodeGen.create(context, true, "to_s", false, EMPTY_BYTES,
                                ReadHashValueNodeGen.create(context, key,
                                        new SourceNode()))));
    }

    @Override
    public void exitFormat(PrintfParser.FormatContext ctx) {
        final int width;

        if (ctx.width != null) {
            width = Integer.parseInt(ctx.width.getText());
        } else {
            width = DEFAULT;
        }

        boolean leftJustified = false;
        boolean hasPlusFlag = false;
        boolean hasSpaceFlag = false;
        boolean hasStarFlag = false;
        boolean hasZeroFlag = false;
        boolean useAlternativeFormat = false;
        int absoluteArgumentIndex = DEFAULT;

        for (int n = 0; n < ctx.flag().size(); n++) {
            final PrintfParser.FlagContext flag = ctx.flag(n);

            if (flag.MINUS() != null) {
                leftJustified = true;
            } else if (flag.SPACE() != null) {
                hasSpaceFlag = true;
            } else if (flag.ZERO() != null) {
                hasZeroFlag = true;
            } else if (flag.STAR() != null) {
                hasStarFlag = true;
            } else if (flag.PLUS() != null) {
                hasPlusFlag = true;
            } else if (flag.HASH() != null) {
                useAlternativeFormat = true;
            } else if (flag.argumentIndex != null) {
                absoluteArgumentIndex = Integer.parseInt(flag.argumentIndex.getText());
            } else {
                throw new UnsupportedOperationException();
            }
        }

        final char type = ctx.TYPE().getSymbol().getText().charAt(0);

        final FormatNode valueNode;

        if (ctx.ANGLE_KEY() != null) {
            final byte[] keyBytes = tokenAsBytes(ctx.ANGLE_KEY().getSymbol(), 1);
            final DynamicObject key = context.getSymbolTable().getSymbol(context.getRopeTable().getRope(keyBytes, USASCIIEncoding.INSTANCE, CodeRange.CR_7BIT));
            valueNode = ReadHashValueNodeGen.create(context, key, new SourceNode());
        } else if (absoluteArgumentIndex != DEFAULT){
            valueNode = ReadArgumentIndexValueNodeGen.create(context, absoluteArgumentIndex, new SourceNode());
        } else {
            valueNode = ReadValueNodeGen.create(context, new SourceNode());
        }

        final int precision;

        if (ctx.precision != null) {
            precision = Integer.parseInt(ctx.precision.getText());
        } else {
            precision = DEFAULT;
        }

        final FormatNode node;

        switch (type) {
            case 's':
            case 'p':
                final String conversionMethodName = type == 's' ? "to_s" : "inspect";
                final FormatNode conversionNode;

                if (ctx.ANGLE_KEY() == null) {
                    conversionNode = ReadStringNodeGen.create(context, true, conversionMethodName, false, EMPTY_BYTES, new SourceNode());
                } else {
                    conversionNode = ToStringNodeGen.create(context, true, conversionMethodName, false, EMPTY_BYTES, valueNode);
                }

                if (width == DEFAULT) {
                    node = WriteBytesNodeGen.create(context, conversionNode);
                } else {
                    node = WritePaddedBytesNodeGen.create(context, width, leftJustified, conversionNode);
                }

                break;
            case 'b':
            case 'B':
            case 'd':
            case 'i':
            case 'o':
            case 'u':
            case 'x':
            case 'X':
                final FormatNode widthNode;
                if (hasStarFlag) {
                    widthNode = ReadIntegerNodeGen.create(context, new SourceNode());
                } else {
                    widthNode = new LiteralFormatNode(context, width);
                }

                final char format;

                switch (type) {
                    case 'b':
                    case 'B':
                        format = type;
                        break;
                    case 'd':
                    case 'i':
                    case 'u':
                        format = 'd';
                        break;
                    case 'o':
                        format = 'o';
                        break;
                    case 'x':
                    case 'X':
                        format = type;
                        break;
                    default:
                        throw new UnsupportedOperationException();
                }

                if(type == 'b' || type == 'B'){
                    node = WriteBytesNodeGen.create(context,
                        FormatIntegerBinaryNodeGen.create(context, format, precision, hasPlusFlag, useAlternativeFormat,
                            leftJustified,
                            hasSpaceFlag,
                            hasZeroFlag,
                            widthNode,
                            ToIntegerNodeGen.create(context, valueNode)));
                } else {
                    node = WriteBytesNodeGen.create(context,
                        FormatIntegerNodeGen.create(context, format, hasSpaceFlag, hasZeroFlag, precision,
                            widthNode,
                            ToIntegerNodeGen.create(context, valueNode)));
                }
                break;
            case 'f':
            case 'e':
            case 'E':
                node = WriteBytesNodeGen.create(context,
                        FormatFloatNodeGen.create(context, width, precision,
                                type, hasSpaceFlag, hasZeroFlag,
                                ToDoubleWithCoercionNodeGen.create(context,
                                        valueNode)));
                break;
            case 'g':
            case 'G':
                node = WriteBytesNodeGen.create(context,
                        FormatFloatHumanReadableNodeGen.create(context,
                                ToDoubleWithCoercionNodeGen.create(context,
                                        valueNode)));
                break;
            default:
                throw new UnsupportedOperationException();
        }

        sequence.add(node);
    }

    @Override
    public void exitLiteral(PrintfParser.LiteralContext ctx) {
        final byte[] text = tokenAsBytes(ctx.LITERAL().getSymbol());

        final FormatNode node;

        if (text.length == 1) {
            node = WriteByteNodeGen.create(context, new LiteralFormatNode(context, text[0]));
        } else {
            node = WriteBytesNodeGen.create(context, new LiteralFormatNode(context, text));
        }

        sequence.add(node);
    }

    public FormatNode getNode() {
        return new SequenceNode(context, sequence.toArray(new FormatNode[sequence.size()]));
    }

    private byte[] tokenAsBytes(Token token) {
        return tokenAsBytes(token, 0);
    }

    private byte[] tokenAsBytes(Token token, int trim) {
        final int from = token.getStartIndex() + trim;
        final int to = from + token.getStopIndex() - token.getStartIndex() + 1 - 2 * trim;
        return Arrays.copyOfRange(source, from, to);
    }

}
