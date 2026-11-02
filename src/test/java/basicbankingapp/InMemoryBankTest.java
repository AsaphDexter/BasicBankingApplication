package basicbankingapp;

/** Runs the shared bank tests against the in-memory repository. */
class InMemoryBankTest extends AbstractBankTest {

    @Override
    protected BankRepository createRepository() {
        return new InMemoryBankRepository();
    }
}
