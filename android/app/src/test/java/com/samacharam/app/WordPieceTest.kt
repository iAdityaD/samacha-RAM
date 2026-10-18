package com.samacharam.app
import org.junit.Assert.*
import org.junit.Test
class WordPieceTest {
    private val vocab=mapOf("[CLS]" to 1,"[SEP]" to 2,"[UNK]" to 3,"hello" to 4,"world" to 5,"##s" to 6)
    @Test fun greedyPieces(){ assertArrayEquals(longArrayOf(1,4,5,6,2),WordPiece(vocab).encode("Hello worlds")) }
    @Test fun unknownIsNotSilentlySplit(){ assertArrayEquals(longArrayOf(1,3,2),WordPiece(vocab).encode("unseen")) }
    @Test fun limitKeepsSpecialTokens(){ assertArrayEquals(longArrayOf(1,4,2),WordPiece(vocab).encode("hello world",3)) }
}
