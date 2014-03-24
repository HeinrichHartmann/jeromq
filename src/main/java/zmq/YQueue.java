/*
    Copyright (c) 2009-2011 250bpm s.r.o.
    Copyright (c) 2007-2009 iMatix Corporation
    Copyright (c) 2007-2011 Other contributors as noted in the AUTHORS file

    This file is part of 0MQ.

    0MQ is free software; you can redistribute it and/or modify it under
    the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation; either version 3 of the License, or
    (at your option) any later version.

    0MQ is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package zmq;

/**
 * Allocation-efficient queue class
 *
 * Memory is allocated as linked list of chunks of specified size:
 *
 * Chunk 0                   Chunk 1Allocation-efficient queue to store pipe items.
 * [ 0 1 ... size-1 ] <---> [ size size+1 ... 2*size-1 ]
 *
 * Chunks are dynamically created when new elements are pushed to the queue
 * and deleted when elements are popped from the queue. A spare chunk is
 * held back to account for refill of the queue.
 *
 * @param <T>   type of elements to be held in the queue.
 */
public class YQueue<T> {

    /**
     *  Individual memory chunk to hold N elements.
     */
    private class Chunk<T>
    {
         final T[] values;  // values held by the chunk
         final int[] pos;   // positions of values in global list
         Chunk prev;        // pointer to prev chung
         Chunk next;        // pointer to next chunk

        /**
         * @param size          allocated capacity of the chunk
         * @param memory_ptr    index of the first element in global sorting
         */
         protected Chunk(int size, int memory_ptr) {
             values = (T[]) new Object[size];
             pos = new int[size];
             for (int i=0; i != values.length; i++) {
                 pos[i] = memory_ptr;
                 memory_ptr++;
             }
         }
    };

    //  Back position may point to invalid memory if the queue is empty,
    //  while begin & end positions are always valid. Begin position is
    //  accessed exclusively be queue reader (front/pop), while back and
    //  end positions are accessed exclusively by queue writer (back/push).
    private Chunk<T> begin_chunk;
    private int begin_pos;
    private Chunk<T> back_chunk;
    private int back_pos;
    private Chunk<T> end_chunk;
    private int end_pos;
    private volatile Chunk<T> spare_chunk;
    private final int size;

    //  People are likely to produce and consume at similar rates.  In
    //  this scenario holding onto the most recently freed chunk saves
    //  us from having to call malloc/free.

    private int memory_ptr;

    /**
     * @param size  initial capacity of Queue
     */
    public YQueue(int size) {
        this.size = size;
        memory_ptr = 0;
        begin_chunk = new Chunk(size, memory_ptr);  // pointer to first chunk of memory
        memory_ptr += size;         // global index of next memory position to be allocated
        begin_pos = 0;              // local index of first element in begin_chunk
        back_pos = 0;               // local index of last element in back_chunnk
        back_chunk = begin_chunk;   // pointer chunk that contians the last element
        spare_chunk = begin_chunk;  // buffer chunk before deallocation
        end_chunk = begin_chunk;    // ??
        end_pos = 1;
    }

    public final int front_pos() {
        return begin_chunk.pos[begin_pos];
    }

    //  Returns reference to the front element of the queue.
    //  If the queue is empty, behaviour is undefined.
    public final T front() {
        return begin_chunk.values [begin_pos];
    }

    public final int back_pos() {
        return back_chunk.pos [back_pos];
    }

    //  Returns reference to the back element of the queue.
    //  If the queue is empty, behaviour is undefined.
    public final T back() {
        return back_chunk.values [back_pos];
    }

    public final T pop() {
        T val = begin_chunk.values [begin_pos];
        begin_chunk.values [begin_pos] = null;
        begin_pos++;
        if (begin_pos == size) {
            begin_chunk = begin_chunk.next;
            begin_chunk.prev = null;
            begin_pos = 0;
        }
        return val;
    }

    /**
     * Adds an element to the back end of the queue. Resizes queue if necessary.
     * @param val
     */
    public final void push(T val) {
        // store value
        back_chunk.values [back_pos] = val;
        back_chunk = end_chunk;
        back_pos = end_pos;

        end_pos ++;
        if (end_pos != size)
            return;

        // allocate new space
        Chunk sc = spare_chunk;
        if (sc != begin_chunk) {
            // have spare chunk
            spare_chunk = spare_chunk.next;
            end_chunk.next = sc;
            sc.prev = end_chunk;
        } else {
            // if no chunk spare allocate new chunk.
            end_chunk.next =  new Chunk(size, memory_ptr);
            memory_ptr += size;
            end_chunk.next.prev = end_chunk;
        }
        end_chunk = end_chunk.next;
        end_pos = 0;
    }

    //  Removes element from the back end of the queue. In other words
    //  it rollbacks last push to the queue. Take care: Caller is
    //  responsible for destroying the object being unpushed.
    //  The caller must also guarantee that the queue isn't empty when
    //  unpush is called. It cannot be done automatically as the read
    //  side of the queue can be managed by different, completely
    //  unsynchronised thread.
    public final void unpush() {
        //  First, move 'back' one position backwards.
        if (back_pos > 0)
            back_pos--;
        else {
            back_pos = size - 1;
            back_chunk = back_chunk.prev;
        }

        //  Now, move 'end' position backwards. Note that obsolete end chunk
        //  is not used as a spare chunk. The analysis shows that doing so
        //  would require free and atomic operation per chunk deallocated
        //  instead of a simple free.
        if (end_pos > 0)
            end_pos--;
        else {
            end_pos = size - 1;
            end_chunk = end_chunk.prev;
            end_chunk.next = null;
        }
    }

    @Override
    public String toString() {
        return "YQueue{" + getFill() + "/" + getSize() + "}";
    }

    public int getSize(){
        return size;
    }

    public int getFill(){
        return back_pos() - front_pos();
    }

    public String contents() {
        StringBuffer out = new StringBuffer();
        int iter_start = begin_pos;
        int iter_end   = size - 1;
        for(Chunk<T> currentChunk = begin_chunk; currentChunk != end_chunk; currentChunk = currentChunk.next){
            if (currentChunk == end_chunk) iter_end = end_pos;
            for (int i = iter_start; i <= iter_end; i++ ) {
                out.append(currentChunk.values[i] + " ");
            }
        }
        return out.toString();
    }
}
