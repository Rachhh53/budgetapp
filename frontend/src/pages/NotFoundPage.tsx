import { Link } from "react-router";
import { Card } from "../components/Card";

/** An address that is not one of the app's screens: an old link, or one typed by hand. */
export function NotFoundPage() {
  return (
    <>
      <h1 className="mb-4 text-2xl font-bold md:mb-6 md:text-3xl">We couldn't find that screen</h1>
      <Card>
        <p className="font-medium">Nothing is wrong with your budget: this address just doesn't lead anywhere.</p>
        <p className="mt-1 text-muted">
          Every screen is in the menu, or you can go{" "}
          <Link to="/" className="cursor-pointer font-semibold text-accent-ink underline">
            back to Pay periods
          </Link>
          .
        </p>
      </Card>
    </>
  );
}
