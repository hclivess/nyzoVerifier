package co.nyzo.verifier;

import co.nyzo.verifier.messages.NewVerifierVote;
import co.nyzo.verifier.messages.StatusResponse;

import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class NewVerifierVoteManager {

    // This class manages votes for the next verifier to let into the verification cycle. Each existing verifier has a
    // vote for who should be allowed in next, and the winning verifier is the one who should be admitted. This is a
    // vote that can (and should) be recast many times, as many new verifiers are let into the mesh.

    // As much as possible, this class follows the pattern of BlockVoteManager.

    // The local vote is redundant, but it is a simple and efficient way to store the local vote for responding to
    // node-join messages.
    private static NewVerifierVote localVote = new NewVerifierVote(new byte[FieldByteSize.identifier]);
    private static final Map<ByteBuffer, ByteBuffer> voteMap = new HashMap<>();

    private static int meshLimit = Integer.MAX_VALUE;

    private static byte[] override = new byte[FieldByteSize.identifier];

    public static void setOverride(byte[] override) {
        NewVerifierVoteManager.override = override;
    }

    public static byte[] getOverride() {
        return override;
    }

    public static void updateMeshLimit() {

        try {
            URL url = new URL("https://nyzo.co/meshLimit");
            ReadableByteChannel channel = Channels.newChannel(url.openStream());
            byte[] array = new byte[100];
            ByteBuffer buffer = ByteBuffer.wrap(array);
            while (channel.read(buffer) > 0) { }
            channel.close();

            byte[] value = Arrays.copyOf(array, buffer.position());
            String meshLimitString = new String(value, StandardCharsets.UTF_8);
            meshLimit = Integer.parseInt(meshLimitString);
        } catch (Exception ignored) { }
    }

    public static synchronized void registerVote(byte[] votingIdentifier, NewVerifierVote vote, boolean isLocalVote) {

        // If the voting verifiers list is empty, accept votes from all verifiers. This will happen only rarely, if
        // ever, but this condition is helpful for testing.
        boolean acceptAllVotes = BlockManager.currentCycleLength() == 0;

        // Register the vote. The map ensures that each identifier only gets one vote. Some of the votes may not count.
        // Votes are only counted for verifiers in the previous cycle.
        ByteBuffer votingIdentifierBuffer = ByteBuffer.wrap(votingIdentifier);
        if (BlockManager.verifierInCurrentCycle(votingIdentifierBuffer) || acceptAllVotes) {
            voteMap.put(votingIdentifierBuffer, ByteBuffer.wrap(vote.getIdentifier()));
        }

        if (isLocalVote) {
            localVote = vote;
        }
    }

    public static synchronized void removeOldVotes() {

        // For simplicity, we remove all votes that are not in the current verifier cycle to conserve memory. This will
        // potentially remove verifiers that would be in the verification cycle when they are needed, but that's not
        // a huge concern, as this process does not require absolute or precise vote counts to work properly. In
        // reality, we are highly unlikely to ever lose more than one vote at a time due to this simplification.

        Set<ByteBuffer> verifiers = new HashSet<>(voteMap.keySet());
        Set<ByteBuffer> currentCycle = BlockManager.verifiersInCurrentCycle();
        for (ByteBuffer verifier : verifiers) {
            if (!currentCycle.contains(verifier)) {
                voteMap.remove(verifier);
            }
        }
    }

    public static synchronized List<ByteBuffer> topVerifiers() {

        List<ByteBuffer> topVerifiers = new ArrayList<>();

        Map<ByteBuffer, Integer> votesPerVerifier = new HashMap<>();
        Set<ByteBuffer> votingVerifiers = BlockManager.verifiersInCurrentCycle();

        if (votingVerifiers.size() < meshLimit) {

            // If the voting verifiers list is empty, accept votes from all verifiers. This will happen only rarely, if
            // ever, but this condition is helpful for testing.
            boolean acceptAllVotes = votingVerifiers.isEmpty();

            // Build the vote map.
            for (ByteBuffer votingVerifier : voteMap.keySet()) {
                if (votingVerifiers.contains(votingVerifier) || acceptAllVotes) {
                    ByteBuffer vote = voteMap.get(votingVerifier);
                    if (!BlockManager.verifierInCurrentCycle(vote) && NodeManager.isActive(vote.array())) {
                        Integer votesForVerifier = votesPerVerifier.get(vote);
                        if (votesForVerifier == null) {
                            votesPerVerifier.put(vote, 1);
                        } else {
                            votesPerVerifier.put(vote, votesForVerifier + 1);
                        }
                    }
                }
            }

            // Make and sort the list descending on votes.
            topVerifiers.addAll(votesPerVerifier.keySet());
            Collections.sort(topVerifiers, new Comparator<ByteBuffer>() {
                @Override
                public int compare(ByteBuffer verifierVote1, ByteBuffer verifierVote2) {
                    Integer voteCount1 = votesPerVerifier.get(verifierVote1);
                    Integer voteCount2 = votesPerVerifier.get(verifierVote2);
                    return voteCount2.compareTo(voteCount1);
                }
            });

            // If the verifiers list is empty and this is a new verifier, add it to the list now.
            if (topVerifiers.isEmpty()) {
                ByteBuffer verifierIdentifier = ByteBuffer.wrap(Verifier.getIdentifier());
                if (!votingVerifiers.contains(verifierIdentifier)) {
                    topVerifiers.add(verifierIdentifier);
                }
            }
        }

        StatusResponse.setField("top new", topVerifiers.size() + "");

        return topVerifiers;
    }

    public static synchronized NewVerifierVote getLocalVote() {

        return localVote;
    }
}
