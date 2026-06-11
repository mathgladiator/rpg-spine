/**
 * MIT License
 * 
 * Copyright (C) 2021 - 2025 by Adama Platform Engineering, LLC
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package mg.tokens;

/** Defines a position within a document. Usually, this is a construct within the document */
public class DocumentPosition {
  public static final DocumentPosition ZERO = new DocumentPosition().ingest(0, 0, 0);
  private String source;
  private int endLineIndex;
  private int endLinePosition;
  private int startLineIndex;
  private int startLinePosition;
  private int startByte;
  private int endByte;

  /** initialize with a non-sense position */
  public DocumentPosition() {
    reset();
  }

  /** aggregate the positions together */
  public static DocumentPosition sum(DocumentPosition... positions) {
    DocumentPosition result = new DocumentPosition();
    for (DocumentPosition position : positions) {
      if (position != null) {
        result.ingest(position);
      }
    }
    return result;
  }

  /** @param other another document position to ingest */
  public DocumentPosition ingest(final DocumentPosition other) {
    if (other != null) {
      if (this.source == null) {
        this.source = other.source;
      }
      ingest(other.startLineIndex, other.startLinePosition, other.startByte);
      ingest(other.endLineIndex, other.endLinePosition, other.endByte);
    }
    return this;
  }

  /** ingest the given (line, position) pair */
  public DocumentPosition ingest(final int line, final int position, final int bytePos) {
    if (bytePos < startByte) {
      startByte = bytePos;
    }
    if (bytePos > endByte) {
      endByte = bytePos;
    }
    if (line < startLineIndex) {
      startLineIndex = line;
      startLinePosition = position;
    } else if (line == startLineIndex && position < startLinePosition) {
      startLinePosition = position;
    }
    if (line > endLineIndex) {
      endLineIndex = line;
      endLinePosition = position;
    } else if (line == endLineIndex && position > endLinePosition) {
      endLinePosition = position;
    }
    return this;
  }

  /**
   * ingest the tokens and the bounds of the tokens
   * @param tokens an array of tokens
   */
  public DocumentPosition ingest(final Token... tokens) {
    if (tokens != null) {
      for (final Token token : tokens) {
        if (token != null) {
          if (this.source == null) {
            this.source = token.sourceName;
          }
          if (token.lineStart != Integer.MAX_VALUE) {
            ingest(token.lineStart, token.charStart, token.byteStart);
            ingest(token.lineEnd, token.charEnd, token.byteEnd);
          }
        }
      }
    }
    return this;
  }

  public void reset() {
    startLineIndex = Integer.MAX_VALUE;
    startLinePosition = Integer.MAX_VALUE;
    startByte = Integer.MAX_VALUE;
    endByte = 0;
    endLineIndex = 0;
    endLinePosition = 0;
  }
}
