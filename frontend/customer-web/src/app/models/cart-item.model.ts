export interface CartItem {
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
  /**
   * Copiata dal prodotto al momento dell'aggiunta, cosi' il carrello si
   * disegna senza dover richiedere di nuovo il catalogo.
   *
   * Puo' mancare: i carrelli salvati in localStorage prima che questo
   * campo esistesse non ce l'hanno, e per quelli si ricade sulla banda
   * colorata come per un prodotto senza foto.
   */
  imageUrl?: string | null;
}
