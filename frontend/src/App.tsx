import { useCallback, useState } from "react";
import CreateLinkForm from "./components/CreateLinkForm";
import LinkList from "./components/LinkList";
import logo from "./assets/logo.svg";

export default function App() {
  const [refreshKey, setRefreshKey] = useState(0);

  const handleCreated = useCallback(() => {
    setRefreshKey((key) => key + 1);
  }, []);

  return (
    <>
      <header className="app-header">
        <img src={logo} alt="Nimbloo" />
        <h1>
          Encurtador de Links <span>· serviço interno</span>
        </h1>
      </header>

      <main className="app-main">
        <CreateLinkForm onCreated={handleCreated} />
        <LinkList refreshKey={refreshKey} />
      </main>
    </>
  );
}